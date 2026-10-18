package com.mejta.mejtautils.freeze;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * Freezing: frozen players keep their position (they can look around and chat), and the interactions
 * chosen in the settings are blocked. Freezes are stored ({@code frozen/<uuid>}), so they survive
 * disconnects and restarts and are re-applied on join.
 *
 * <p>The screen is never taken over: there is no unclosable menu. A frozen player sees a boss bar
 * and/or action bar message. Logging out is reported to staff, never punished: it is not proof of
 * cheating, and MejtaUtils has no punishment system. Server thread only.
 */
public final class FreezeManager {
	public static final String CHANNEL = MejtaUtils.ID + ".freeze";
	private static final String FOLDER = "frozen";
	private static final double TOLERANCE = 0.05;

	/** A stored freeze. */
	public record Frozen(UUID player, String name, String byName, String byUuid, String reason, Instant since,
			String dimension, double x, double y, double z, boolean hadFlight, boolean wasFlying) {
		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("player", player.toString());
			o.addProperty("name", name);
			o.addProperty("by", byName);
			o.addProperty("by_uuid", byUuid);
			o.addProperty("reason", reason);
			o.addProperty("since", since.toString());
			o.addProperty("dimension", dimension);
			o.addProperty("x", x);
			o.addProperty("y", y);
			o.addProperty("z", z);
			o.addProperty("had_flight", hadFlight);
			o.addProperty("was_flying", wasFlying);
			return o;
		}

		static Frozen fromJson(JsonObject o) {
			return new Frozen(UUID.fromString(o.get("player").getAsString()), o.get("name").getAsString(), o.get("by").getAsString(),
					o.get("by_uuid").getAsString(), o.get("reason").getAsString(), Instant.parse(o.get("since").getAsString()),
					o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
					o.get("had_flight").getAsBoolean(), o.get("was_flying").getAsBoolean());
		}

		Frozen at(String dimension, double x, double y, double z) {
			return new Frozen(player, name, byName, byUuid, reason, since, dimension, x, y, z, hadFlight, wasFlying);
		}
	}

	/** Counts of blocked actions in the current freeze, for staff diagnostics. */
	public static final class Blocked {
		final Map<String, Integer> counts = new LinkedHashMap<>();

		void add(String what) {
			counts.merge(what, 1, Integer::sum);
		}
	}

	private static final class Bar extends BossEvent {
		Bar(UUID id, Component text) {
			super(id, text, BossBarColor.RED, BossBarOverlay.PROGRESS);
			setProgress(1.0f);
		}
	}

	private final DataStore store;
	private final ConfigHandle config;
	private final RecordLog log;
	private final Map<UUID, Frozen> frozen = new ConcurrentHashMap<>();
	private final Map<UUID, Blocked> blocked = new ConcurrentHashMap<>();
	private int ticks;

	public FreezeManager(DataStore store, ConfigHandle config) {
		this.store = store;
		this.config = config;
		this.log = MejtaCoreAPI.recordLog(MejtaUtils.ID, "freeze", RecordLogOptions.DEFAULT);
		for (String key : store.keys(FOLDER)) {
			store.read(key).ifPresent(json -> {
				try {
					Frozen f = Frozen.fromJson(json);
					frozen.put(f.player(), f);
				} catch (RuntimeException e) {
					MejtaUtils.log().error("Ignoring unreadable freeze record {}: {}", key, e.toString());
				}
			});
		}
	}

	public boolean isFrozen(UUID player) {
		return frozen.containsKey(player);
	}

	public Optional<Frozen> record(UUID player) {
		return Optional.ofNullable(frozen.get(player));
	}

	public List<Frozen> all() {
		return List.copyOf(frozen.values());
	}

	public Map<String, Integer> blockedCounts(UUID player) {
		Blocked b = blocked.get(player);
		return b == null ? Map.of() : Map.copyOf(b.counts);
	}

	/** Counts a blocked action and tells the player why (at most once a second, via the action bar). */
	public void blocked(ServerPlayer player, String what) {
		blocked.computeIfAbsent(player.getUUID(), k -> new Blocked()).add(what);
		player.sendSystemMessage(Component.literal("You are frozen.").withStyle(ChatFormatting.RED), true);
	}

	// ---------------------------------------------------------------- freeze / unfreeze

	/** Freezes an online or offline player; the caller checked permissions and exemptions. */
	public void freeze(UUID target, String name, ServerPlayer online, AuditActor actor, String reason) {
		Frozen record;
		if (online != null) {
			record = new Frozen(target, name, actor.name(), actor.uuid().map(UUID::toString).orElse(""), reason, Instant.now(),
					online.level().dimension().identifier().toString(), online.getX(), online.getY(), online.getZ(),
					online.getAbilities().mayfly, online.getAbilities().flying);
		} else {
			record = new Frozen(target, name, actor.name(), actor.uuid().map(UUID::toString).orElse(""), reason, Instant.now(),
					"", 0, 0, 0, false, false);
		}
		frozen.put(target, record);
		blocked.put(target, new Blocked());
		store.write(DataStore.playerKey(FOLDER, target), record.toJson());
		if (online != null) {
			apply(online);
			online.sendSystemMessage(Component.literal(config.get(UtilsSettings.FREEZE_MESSAGE)).withStyle(ChatFormatting.RED));
		}
		logEvent(target, name, "freeze", Map.of("by", actor.name(), "reason", reason, "online", online != null));
		UtilsAudit.record("freeze.start", actor, target, name, AuditOutcome.SUCCESS, Map.of("reason", reason, "online", online != null),
				UtilsPermissions.FREEZE_INFO);
	}

	public boolean unfreeze(UUID target, AuditActor actor, MinecraftServer server) {
		Frozen record = frozen.remove(target);
		if (record == null) {
			return false;
		}
		store.delete(DataStore.playerKey(FOLDER, target));
		ServerPlayer online = server.getPlayerList().getPlayer(target);
		if (online != null) {
			release(online, record);
			online.sendSystemMessage(Component.literal("You are no longer frozen.").withStyle(ChatFormatting.GREEN));
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("frozen_since", record.since().toString());
		blockedCounts(target).forEach((k, v) -> details.put("blocked_" + k, v));
		blocked.remove(target);
		logEvent(target, record.name(), "unfreeze", Map.of("by", actor.name()));
		UtilsAudit.record("freeze.stop", actor, target, record.name(), AuditOutcome.SUCCESS, details, UtilsPermissions.FREEZE_INFO);
		return true;
	}

	/** Applies a freeze to the online player: hover in place, show the indicator. */
	private void apply(ServerPlayer player) {
		Frozen record = frozen.get(player.getUUID());
		if (record == null) {
			return;
		}
		if (record.dimension().isEmpty()) { // frozen while offline: freeze where they joined
			record = record.at(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ());
			frozen.put(player.getUUID(), record);
			store.write(DataStore.playerKey(FOLDER, player.getUUID()), record.toJson());
		}
		player.stopRiding();
		player.setDeltaMovement(Vec3.ZERO);
		if (config.get(UtilsSettings.FREEZE_MOVEMENT)) {
			// Flight makes the client hover instead of falling, so the position lock does not have to fight gravity.
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
		showIndicator(player);
	}

	private void release(ServerPlayer player, Frozen record) {
		if (config.get(UtilsSettings.FREEZE_MOVEMENT)) {
			player.getAbilities().mayfly = record.hadFlight() || player.isCreative() || player.isSpectator();
			player.getAbilities().flying = record.wasFlying() && player.getAbilities().mayfly;
			player.onUpdateAbilities();
		}
		player.connection.send(ClientboundBossEventPacket.createRemovePacket(barId(player.getUUID())));
	}

	private void showIndicator(ServerPlayer player) {
		Component text = Component.literal(config.get(UtilsSettings.FREEZE_MESSAGE)).withStyle(ChatFormatting.RED);
		UtilsSettings.Indicator indicator = config.get(UtilsSettings.FREEZE_INDICATOR);
		if (indicator != UtilsSettings.Indicator.ACTION_BAR) {
			player.connection.send(ClientboundBossEventPacket.createAddPacket(new Bar(barId(player.getUUID()), text)));
		}
		if (indicator != UtilsSettings.Indicator.BOSS_BAR) {
			player.sendSystemMessage(text, true);
		}
	}

	private static UUID barId(UUID player) {
		return UUID.nameUUIDFromBytes(("mejtautils:freeze:" + player).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	// ---------------------------------------------------------------- ticking, joins, leaves

	/** End of each server tick: keep frozen players in place, refresh the action bar, protect them if configured. */
	public void tick(MinecraftServer server) {
		ticks++;
		for (Frozen record : frozen.values()) {
			ServerPlayer player = server.getPlayerList().getPlayer(record.player());
			if (player == null || record.dimension().isEmpty()) {
				continue;
			}
			if (config.get(UtilsSettings.FREEZE_MOVEMENT)) {
				hold(server, player, record);
			}
			if (ticks % 40 == 0 && config.get(UtilsSettings.FREEZE_INDICATOR) != UtilsSettings.Indicator.BOSS_BAR) {
				player.sendSystemMessage(Component.literal(config.get(UtilsSettings.FREEZE_MESSAGE)).withStyle(ChatFormatting.RED), true);
			}
			if (config.get(UtilsSettings.FREEZE_INVULNERABLE)) {
				player.getFoodData().setFoodLevel(Math.max(player.getFoodData().getFoodLevel(), 20));
				player.setRemainingFireTicks(0);
			}
		}
	}

	private void hold(MinecraftServer server, ServerPlayer player, Frozen record) {
		boolean sameLevel = player.level().dimension().identifier().toString().equals(record.dimension());
		Vec3 anchor = new Vec3(record.x(), record.y(), record.z());
		if (sameLevel && player.position().distanceToSqr(anchor) <= TOLERANCE * TOLERANCE) {
			return;
		}
		ServerLevel level = sameLevel ? player.level() : levelOf(server, record.dimension());
		if (level == null) {
			return;
		}
		if (player.isPassenger()) {
			player.stopRiding();
		}
		player.teleport(new TeleportTransition(level, anchor, Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
		blocked(player, "movement");
	}

	private static ServerLevel levelOf(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		if (id == null) {
			return null;
		}
		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
		return server.getLevel(key);
	}

	public void onJoin(ServerPlayer player) {
		Frozen record = frozen.get(player.getUUID());
		if (record == null) {
			return;
		}
		apply(player);
		player.sendSystemMessage(Component.literal(config.get(UtilsSettings.FREEZE_MESSAGE)).withStyle(ChatFormatting.RED));
		logEvent(player.getUUID(), record.name(), "rejoin", Map.of("ping_ms", player.connection.latency()));
		publish(Severity.WARNING, record.name() + " rejoined while frozen", "They are frozen again.", record);
	}

	public void onLeave(ServerPlayer player) {
		Frozen record = frozen.get(player.getUUID());
		if (record == null) {
			return;
		}
		logEvent(player.getUUID(), record.name(), "disconnect", Map.of("ping_ms", player.connection.latency()));
		if (config.get(UtilsSettings.FREEZE_ON_LOGOUT) == UtilsSettings.LogoutAction.NOTIFY_STAFF) {
			publish(Severity.WARNING, record.name() + " logged out while frozen",
					"This alone is not proof of cheating (connection problems happen). They stay frozen and are frozen again when they rejoin.",
					record);
		}
	}

	private void publish(Severity severity, String title, String message, Frozen record) {
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaUtils.ID, CHANNEL)
				.severity(severity)
				.title(title)
				.message(message)
				.field("player", record.name())
				.field("uuid", record.player())
				.field("frozen_by", record.byName())
				.field("reason", record.reason())
				.field("since", record.since())
				.inGameAudience(UtilsPermissions.FREEZE_INFO)
				.build());
	}

	/** Staff-only diagnostics, {@code mejta-data/mejtautils/freeze.jsonl}. No chat content, no IP addresses. */
	private void logEvent(UUID player, String name, String event, Map<String, ?> data) {
		JsonObject r = new JsonObject();
		r.addProperty("type", "freeze");
		r.addProperty("event", event);
		r.addProperty("player", name);
		r.addProperty("uuid", player.toString());
		data.forEach((k, v) -> r.addProperty(k, String.valueOf(v)));
		log.append(r);
	}
}
