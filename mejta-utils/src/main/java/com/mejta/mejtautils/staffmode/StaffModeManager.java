package com.mejta.mejtautils.staffmode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * Staff mode. The survival state is saved to disk (synchronously, fsync'd) before anything about the
 * player changes; each state change is written before it happens ({@link SessionState}). A crash at
 * any point is recovered on the next join, or, where it cannot be decided safely, nothing is changed
 * and the staff member is asked to confirm. Server thread only.
 */
public final class StaffModeManager {
	public static final String CHANNEL = MejtaUtils.ID + ".staffmode";
	private static final String FOLDER = "staffmode";
	private static final String HISTORY = "staffmode-history";

	public enum ExitResult {
		DONE,
		NOT_ACTIVE,
		NEEDS_CONFIRMATION,
		FAILED
	}

	private final DataStore store;
	private final ConfigHandle config;
	/** Sessions by player; also the "is in staff mode" answer for every safety check. */
	private final Map<UUID, JsonObject> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, List<String>> waitingForConfirmation = new ConcurrentHashMap<>();

	public StaffModeManager(DataStore store, ConfigHandle config) {
		this.store = store;
		this.config = config;
		for (String key : store.keys(FOLDER)) {
			store.read(key).ifPresent(json -> {
				try {
					sessions.put(UUID.fromString(json.get("uuid").getAsString()), json);
				} catch (RuntimeException e) {
					MejtaUtils.log().error("Ignoring unreadable staff-mode session {}: {}", key, e.toString());
				}
			});
		}
	}

	/** In staff mode (or a staff-mode session is unfinished): containers are view-only, drops and pickups blocked. */
	public boolean isActive(UUID player) {
		return sessions.containsKey(player);
	}

	/** Re-reads one player's session from disk (after an admin edited or removed the file, and in tests). */
	public void reload(UUID player) {
		Optional<JsonObject> stored = store.read(key(player));
		if (stored.isPresent()) {
			sessions.put(player, stored.get());
		} else {
			sessions.remove(player);
		}
		waitingForConfirmation.remove(player);
	}

	public Optional<JsonObject> session(UUID player) {
		return Optional.ofNullable(sessions.get(player)).map(JsonObject::deepCopy);
	}

	public List<JsonObject> sessions() {
		return List.copyOf(sessions.values());
	}

	public Optional<List<String>> confirmationNeeded(UUID player) {
		return Optional.ofNullable(waitingForConfirmation.get(player));
	}

	private static String key(UUID player) {
		return DataStore.playerKey(FOLDER, player);
	}

	private static SessionState state(JsonObject session) {
		return SessionState.valueOf(session.get("state").getAsString());
	}

	private void write(UUID player, JsonObject session) throws IOException {
		store.writeNow(key(player), session);
		sessions.put(player, session);
	}

	// ---------------------------------------------------------------- entering

	public boolean enter(ServerPlayer player, AuditActor actor) {
		UUID id = player.getUUID();
		if (isActive(id)) {
			player.sendSystemMessage(Component.literal("You are already in staff mode.").withStyle(ChatFormatting.YELLOW));
			return false;
		}
		if (MejtaUtils.freeze().isFrozen(id)) {
			player.sendSystemMessage(Component.literal("You cannot enter staff mode while frozen.").withStyle(ChatFormatting.RED));
			return false;
		}
		JsonObject snapshot;
		try {
			snapshot = StaffSnapshot.capture(player);
		} catch (RuntimeException e) {
			MejtaUtils.log().error("Could not save the state of {}; staff mode refused", player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal("Your state could not be saved, so staff mode was not started. Nothing changed.")
					.withStyle(ChatFormatting.RED));
			return false;
		}
		JsonObject session = new JsonObject();
		session.addProperty("format", 1);
		session.addProperty("session", UUID.randomUUID().toString());
		session.addProperty("state", SessionState.ENTERING.name());
		session.addProperty("uuid", id.toString());
		session.addProperty("name", player.getGameProfile().name());
		session.addProperty("started", Instant.now().toString());
		boolean vanish = config.get(UtilsSettings.STAFF_VANISH) && !MejtaUtils.vanish().isHidden(id);
		session.addProperty("vanished_by_staffmode", vanish);
		session.add("snapshot", snapshot);
		session.addProperty("hash", StaffSnapshot.hash(snapshot));
		try {
			write(id, session); // on disk before anything changes
		} catch (IOException e) {
			MejtaUtils.log().error("Could not write the staff-mode session of {}", player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal("Your state could not be saved to disk, so staff mode was not started. Nothing changed.")
					.withStyle(ChatFormatting.RED));
			return false;
		}
		if (vanish) {
			MejtaUtils.vanish().setVanished(player, true, actor);
		}
		GameType mode = config.get(UtilsSettings.STAFF_GAMEMODE) == UtilsSettings.StaffGameMode.CREATIVE ? GameType.CREATIVE : GameType.ADVENTURE;
		StaffSnapshot.applyStaffState(player, mode, config.get(UtilsSettings.STAFF_FLIGHT));
		session.addProperty("state", SessionState.ACTIVE.name());
		try {
			write(id, session);
		} catch (IOException e) {
			// Still ENTERING on disk with only tools in the inventory: the next join resumes, never loses anything.
			MejtaUtils.log().error("Could not mark the staff-mode session of {} active", player.getGameProfile().name(), e);
		}
		player.sendSystemMessage(Component.literal("Staff mode on. Your survival state is saved; /staffmode off restores it.")
				.withStyle(ChatFormatting.AQUA));
		UtilsAudit.record("staffmode.enter", actor, id, player.getGameProfile().name(), AuditOutcome.SUCCESS,
				Map.of("session", session.get("session").getAsString(), "game_mode", mode.getName()), UtilsPermissions.STAFFMODE);
		return true;
	}

	// ---------------------------------------------------------------- leaving

	/** Restores the saved state. Without {@code confirmed}, refuses if restoring is not clearly safe. */
	public ExitResult exit(ServerPlayer player, boolean confirmed, AuditActor actor) {
		UUID id = player.getUUID();
		JsonObject session = sessions.get(id);
		if (session == null) {
			return ExitResult.NOT_ACTIVE;
		}
		MinecraftServer server = player.level().getServer();
		JsonObject snapshot = session.getAsJsonObject("snapshot");
		boolean intact = StaffSnapshot.hash(snapshot).equals(session.get("hash").getAsString())
				&& StaffSnapshot.itemsDecode(snapshot, server.registryAccess());
		List<String> problems = RestoreCheck.problems(intact, StaffSnapshot.level(server, snapshot).isPresent(),
				StaffSnapshot.sameGameVersion(snapshot));
		if (!problems.isEmpty() && !confirmed) {
			waitingForConfirmation.put(id, problems);
			return ExitResult.NEEDS_CONFIRMATION;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("session", session.get("session").getAsString());
		if (confirmed) {
			details.put("confirmed_problems", String.join("; ", problems));
			details.put("replaced_inventory", describeInventory(player));
		}
		try {
			session.addProperty("state", SessionState.RESTORING.name());
			write(id, session);
			StaffSnapshot.restore(player, snapshot, server.overworld());
			if (session.get("vanished_by_staffmode").getAsBoolean()) {
				MejtaUtils.vanish().setVanished(player, false, actor);
			}
			server.getPlayerList().saveAll(); // the restored state is in the player's save before the session ends
			finish(id, session, "restored");
		} catch (IOException | RuntimeException e) {
			MejtaUtils.log().error("Restoring the staff-mode state of {} failed", player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal("Restoring failed; your saved state is kept. Try /staffmode off again or ask an admin.")
					.withStyle(ChatFormatting.RED));
			UtilsAudit.record("staffmode.exit", actor, id, player.getGameProfile().name(), AuditOutcome.FAILED, details, UtilsPermissions.STAFFMODE);
			return ExitResult.FAILED;
		}
		waitingForConfirmation.remove(id);
		player.sendSystemMessage(Component.literal("Staff mode off. Your survival state is back.").withStyle(ChatFormatting.AQUA));
		UtilsAudit.record("staffmode.exit", actor, id, player.getGameProfile().name(), AuditOutcome.SUCCESS, details, UtilsPermissions.STAFFMODE);
		return ExitResult.DONE;
	}

	/** Moves the session to the history and forgets it. */
	private void finish(UUID id, JsonObject session, String outcome) throws IOException {
		session.addProperty("finished", Instant.now().toString());
		session.addProperty("outcome", outcome);
		String historyKey = HISTORY + "/" + id.toString().toLowerCase(java.util.Locale.ROOT) + "-" + System.currentTimeMillis();
		store.writeNow(historyKey, session);
		store.delete(key(id));
		store.flush().join();
		sessions.remove(id);
		pruneHistory(id);
	}

	private void pruneHistory(UUID id) {
		String prefix = HISTORY + "/" + id.toString().toLowerCase(java.util.Locale.ROOT) + "-";
		List<String> mine = new ArrayList<>();
		for (String k : store.keys(HISTORY)) {
			if (k.startsWith(prefix)) {
				mine.add(k);
			}
		}
		mine.sort(null); // the suffix is a millisecond time, same length for centuries
		int keep = config.get(UtilsSettings.STAFF_HISTORY);
		for (int i = 0; i < mine.size() - keep; i++) {
			store.delete(mine.get(i));
		}
	}

	// ---------------------------------------------------------------- joins, respawns

	public void onJoin(ServerPlayer player) {
		UUID id = player.getUUID();
		JsonObject session = sessions.get(id);
		if (session == null) {
			int removed = StaffTools.sweep(player.getInventory()) + StaffTools.sweep(player.getEnderChestInventory());
			if (removed > 0) {
				UtilsAudit.record("staffmode.tools_removed", AuditActor.system(MejtaUtils.ID), id, player.getGameProfile().name(),
						AuditOutcome.SUCCESS, Map.of("stacks", removed), UtilsPermissions.STAFFMODE);
			}
			return;
		}
		MinecraftServer server = player.level().getServer();
		JsonObject snapshot = session.getAsJsonObject("snapshot");
		boolean intact = StaffSnapshot.hash(snapshot).equals(session.get("hash").getAsString())
				&& StaffSnapshot.itemsDecode(snapshot, server.registryAccess());
		SessionState.JoinAction action = state(session).onJoin(intact, StaffSnapshot.inventoryMatches(player, snapshot), onlyTools(player));
		AuditActor system = AuditActor.system(MejtaUtils.ID);
		try {
			switch (action) {
				case RESUME -> {
					session.addProperty("state", SessionState.ACTIVE.name());
					write(id, session);
					if (session.get("vanished_by_staffmode").getAsBoolean() && !MejtaUtils.vanish().isHidden(id)) {
						MejtaUtils.vanish().setVanished(player, true, system);
					}
					GameType mode = config.get(UtilsSettings.STAFF_GAMEMODE) == UtilsSettings.StaffGameMode.CREATIVE ? GameType.CREATIVE : GameType.ADVENTURE;
					StaffSnapshot.applyStaffState(player, mode, config.get(UtilsSettings.STAFF_FLIGHT));
					player.sendSystemMessage(Component.literal("You are still in staff mode. /staffmode off restores your survival state.")
							.withStyle(ChatFormatting.AQUA));
				}
				case REPEAT_RESTORE -> {
					StaffSnapshot.restore(player, snapshot, server.overworld());
					if (session.get("vanished_by_staffmode").getAsBoolean()) {
						MejtaUtils.vanish().setVanished(player, false, system);
					}
					server.getPlayerList().saveAll();
					finish(id, session, "restored after restart");
					player.sendSystemMessage(Component.literal("Staff mode had been ending when the server stopped; your survival state is back.")
							.withStyle(ChatFormatting.AQUA));
				}
				case CANCEL_ENTRY -> {
					finish(id, session, "cancelled: the server stopped before staff mode started");
					if (session.get("vanished_by_staffmode").getAsBoolean() && MejtaUtils.vanish().isHidden(id)) {
						MejtaUtils.vanish().setVanished(player, false, system);
					}
				}
				case ASK -> {
					List<String> reasons = new ArrayList<>();
					if (!intact) {
						reasons.add("the saved state is damaged");
					}
					reasons.add("your current inventory is neither your saved one nor only staff tools");
					waitingForConfirmation.put(id, reasons);
					player.sendSystemMessage(Component.literal("Staff mode could not be recovered automatically: " + String.join("; ", reasons)
							+ ". Nothing was changed. /staffmode restore confirm replaces your current inventory with the saved one "
							+ "(recorded in the audit log).").withStyle(ChatFormatting.RED));
					MejtaCoreAPI.notifications().publish(Notification.builder(MejtaUtils.ID, CHANNEL)
							.severity(Severity.WARNING)
							.title("Staff-mode recovery of " + player.getGameProfile().name() + " needs confirmation")
							.message(String.join("; ", reasons))
							.field("player", player.getGameProfile().name())
							.field("uuid", id)
							.inGameAudience(UtilsPermissions.STAFFMODE_RESTORE)
							.build());
				}
			}
		} catch (IOException | RuntimeException e) {
			MejtaUtils.log().error("Staff-mode recovery of {} failed", player.getGameProfile().name(), e);
		}
		UtilsAudit.record("staffmode.recover", system, id, player.getGameProfile().name(),
				action == SessionState.JoinAction.ASK ? AuditOutcome.CONFLICT : AuditOutcome.SUCCESS,
				Map.of("state", state(session).name(), "action", action.name()), UtilsPermissions.STAFFMODE);
	}

	/** After respawning in staff mode (e.g. /kill), the tools are given again. */
	public void onRespawn(ServerPlayer player) {
		if (isActive(player.getUUID())) {
			GameType mode = config.get(UtilsSettings.STAFF_GAMEMODE) == UtilsSettings.StaffGameMode.CREATIVE ? GameType.CREATIVE : GameType.ADVENTURE;
			StaffSnapshot.applyStaffState(player, mode, config.get(UtilsSettings.STAFF_FLIGHT));
		}
	}

	private static boolean onlyTools(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && !StaffTools.isTool(stack)) {
				return false;
			}
		}
		return true;
	}

	private static String describeInventory(ServerPlayer player) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < player.getInventory().getContainerSize() && out.length() < 400; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && !StaffTools.isTool(stack)) {
				out.append(i).append('=').append(ItemJson.describe(stack)).append("; ");
			}
		}
		return out.isEmpty() ? "only staff tools" : out.toString();
	}
}
