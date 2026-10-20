package com.mejta.mejtaqol.teleport;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.util.Durations;
import com.mejta.mejtaqol.util.Msg;

/**
 * Every MejtaQoL teleport goes through here: the combat lock (MejtaCore's combat status), the cooldown, the wait (cancelled by
 * moving or taking damage), the cross-dimension rule and {@code /back}. Server thread only.
 */
public final class Teleports {
	/**
	 * A teleporting feature.
	 *
	 * @param id         cooldown key and name in messages, e.g. {@code home}
	 * @param enabled    its on/off setting
	 * @param permission re-checked when the wait ends
	 * @param cooldown   seconds between two uses; {@code null} if the feature has no teleport cooldown
	 */
	public record Feature(String id, Setting<Boolean> enabled, MejtaPermission permission, Setting<Integer> cooldown) {
	}

	/** Where a teleport goes, worked out when it is requested and again when the wait ends. */
	@FunctionalInterface
	public interface Target {
		Result resolve(ServerPlayer traveller);
	}

	/** A destination, or why there is none. */
	public record Result(Location location, Component error) {
		public static Result to(Location location) {
			return new Result(location, null);
		}

		public static Result fail(String reason) {
			return new Result(null, Msg.error(reason));
		}
	}

	private static final class Pending {
		final Feature feature;
		final Component label;
		final Target target;
		final Location start;
		int ticksLeft;

		Pending(Feature feature, Component label, Target target, Location start, int ticksLeft) {
			this.feature = feature;
			this.label = label;
			this.target = target;
			this.start = start;
			this.ticksLeft = ticksLeft;
		}
	}

	/** Moving further than this (in blocks) during the wait cancels it. */
	static final double MOVE_TOLERANCE = 0.5;

	private final ConfigHandle config;
	private final Cooldowns cooldowns = new Cooldowns();
	private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
	private final Map<UUID, Location> back = new ConcurrentHashMap<>();

	public Teleports(ConfigHandle config) {
		this.config = config;
	}

	public Cooldowns cooldowns() {
		return cooldowns;
	}

	public boolean bypasses(ServerPlayer player) {
		return MejtaCoreAPI.hasPermission(player, QolPermissions.TELEPORT_BYPASS);
	}

	/**
	 * Why the player may not start a teleport of this feature now (combat lock or cooldown), or empty if
	 * they may. Players with {@code mejtaqol.teleport.bypass} always may.
	 */
	public Optional<Component> refusal(ServerPlayer player, Feature feature) {
		if (bypasses(player)) {
			return Optional.empty();
		}
		long now = System.currentTimeMillis();
		long combat = MejtaCoreAPI.status().combatRemainingMillis(player.getUUID());
		if (combat > 0) {
			return Optional.of(Msg.error("You are in combat. You can teleport again in " + Durations.format(combat) + "."));
		}
		if (feature.cooldown() != null) {
			long wait = cooldowns.remaining(player.getUUID(), feature.id(), config.get(feature.cooldown()), now);
			if (wait > 0) {
				return Optional.of(Msg.error("You can use /" + feature.id() + " again in " + Durations.format(wait) + "."));
			}
		}
		return Optional.empty();
	}

	/**
	 * Starts a teleport of {@code player}. Checks the combat lock, cooldown and destination now, then
	 * waits {@code teleport.warmup_seconds} (none with the bypass permission). Returns false and tells
	 * the player why if it cannot start. A new request replaces the player's waiting one.
	 */
	public boolean request(ServerPlayer player, Feature feature, Component label, Target target) {
		Optional<Component> refused = refusal(player, feature);
		if (refused.isPresent()) {
			player.sendSystemMessage(refused.get());
			return false;
		}
		Result first = target.resolve(player);
		if (first.error() != null) {
			player.sendSystemMessage(first.error());
			return false;
		}
		Optional<Component> dimension = dimensionRefusal(player, first.location());
		if (dimension.isPresent()) {
			player.sendSystemMessage(dimension.get());
			return false;
		}
		int warmup = bypasses(player) ? 0 : config.get(QolSettings.WARMUP_SECONDS);
		Pending replaced = pending.remove(player.getUUID());
		if (replaced != null) {
			player.sendSystemMessage(Msg.info("Your earlier teleport was replaced."));
		}
		Pending p = new Pending(feature, label, target, Locations.of(player), warmup * 20);
		if (warmup <= 0) {
			complete(player, p);
			return true;
		}
		pending.put(player.getUUID(), p);
		player.sendSystemMessage(Msg.info("Teleporting to ").append(label.copy().withStyle(ChatFormatting.GOLD))
				.append(Msg.info(" in " + warmup + " s." + (config.get(QolSettings.CANCEL_ON_MOVE) ? " Don't move." : ""))));
		return true;
	}

	/** Whether the player has a teleport waiting. */
	public boolean isWaiting(UUID player) {
		return pending.containsKey(player);
	}

	/** Cancels the player's waiting teleport, telling them why. */
	public void cancel(ServerPlayer player, String reason) {
		if (pending.remove(player.getUUID()) != null) {
			player.sendSystemMessage(Msg.error("Teleport cancelled: " + reason + "."));
			Msg.actionBar(player, Component.empty());
		}
	}

	/** End of every server tick: counts down and completes or cancels waiting teleports. */
	public void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Pending> e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			Pending p = e.getValue();
			if (player == null || player.isRemoved() || !player.isAlive()) {
				it.remove();
				continue;
			}
			Location now = Locations.of(player);
			boolean otherDimension = !now.dimension().equals(p.start.dimension());
			if (otherDimension || (config.get(QolSettings.CANCEL_ON_MOVE) && now.distanceSquared(p.start) > MOVE_TOLERANCE * MOVE_TOLERANCE)) {
				it.remove();
				player.sendSystemMessage(Msg.error("Teleport cancelled: you moved."));
				Msg.actionBar(player, Component.empty());
				continue;
			}
			p.ticksLeft--;
			if (p.ticksLeft <= 0) {
				it.remove();
				Msg.actionBar(player, Component.empty());
				complete(player, p);
			} else if (p.ticksLeft % 20 == 0) {
				Msg.actionBar(player, Msg.highlight("Teleporting in " + p.ticksLeft / 20 + "..."));
			}
		}
	}

	private void complete(ServerPlayer player, Pending p) {
		if (!config.get(p.feature.enabled()) || !MejtaCoreAPI.hasPermission(player, p.feature.permission())) {
			player.sendSystemMessage(Msg.error("Teleport cancelled: /" + p.feature.id() + " is no longer available to you."));
			return;
		}
		Result result = p.target.resolve(player);
		if (result.error() != null) {
			player.sendSystemMessage(result.error());
			return;
		}
		Optional<Component> dimension = dimensionRefusal(player, result.location());
		if (dimension.isPresent()) {
			player.sendSystemMessage(dimension.get());
			return;
		}
		Location from = Locations.of(player);
		if (!Locations.teleport(player, result.location())) {
			player.sendSystemMessage(Msg.error("That place is in a dimension that no longer exists."));
			return;
		}
		back.put(player.getUUID(), from);
		cooldowns.use(player.getUUID(), p.feature.id(), System.currentTimeMillis());
		player.sendSystemMessage(Msg.success("Teleported to ").append(p.label.copy().withStyle(ChatFormatting.GOLD)).append(Msg.success(".")));
	}

	private Optional<Component> dimensionRefusal(ServerPlayer player, Location to) {
		if (config.get(QolSettings.CROSS_DIMENSION) || bypasses(player)
				|| to.dimension().equals(Locations.dimension(player.level()))) {
			return Optional.empty();
		}
		return Optional.of(Msg.error("Teleporting into another dimension is turned off on this server."));
	}

	// ---------------------------------------------------------------- /back

	/** Where {@code /back} goes: before the last teleport, or the death point. */
	public Optional<Location> backLocation(UUID player) {
		return Optional.ofNullable(back.get(player));
	}

	public void setBack(UUID player, Location location) {
		back.put(player, location);
	}

	// ---------------------------------------------------------------- damage and combat

	/** After damage was applied to an entity. */
	public void onDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer victim) || amount <= 0) {
			return;
		}
		if (config.get(QolSettings.CANCEL_ON_DAMAGE)) {
			cancel(victim, "you took damage");
		}
		// MejtaCore puts both players in combat; an attacker's waiting teleport ends here.
		if (source.getEntity() instanceof ServerPlayer attacker && attacker != victim && MejtaCoreAPI.status().inCombat(attacker.getUUID())) {
			cancel(attacker, "you are in combat");
		}
	}

	public void onLeave(UUID player) {
		pending.remove(player);
	}

	/** Server start and stop: nothing carries over from another world. */
	public void reset() {
		pending.clear();
		back.clear();
		cooldowns.clearAll();
	}
}
