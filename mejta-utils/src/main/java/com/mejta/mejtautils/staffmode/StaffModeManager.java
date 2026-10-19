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
import com.mejta.mejtautils.inspect.Inspect;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.PlayerSaves;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * Staff mode. The survival state is saved to disk (synchronously, fsync'd) before anything about the
 * player changes; each state change is written before it happens ({@link SessionState}). A crash at
 * any point is recovered on the next join, or, where it cannot be decided safely, nothing is changed
 * and the staff member is asked to confirm. Server thread only.
 *
 * <p>Leaving writes {@code RESTORING}, replaces the survival state, saves the player, then writes the
 * {@code restored} marker. With the marker the session is only finished, never restored again: by then
 * the live inventory is the real one (the player may have used or placed items since).
 */
public final class StaffModeManager {
	public static final String CHANNEL = MejtaUtils.ID + ".staffmode";
	private static final String FOLDER = "staffmode";
	private static final String HISTORY = "staffmode-history";
	/** Session field: when the survival state was restored and saved. */
	private static final String RESTORED = "restored";
	/** Session field (in memory until the session is finished): the saved position was restored. */
	private static final String POSITION_RESTORED = "position_restored";

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

	/** Sessions are loaded by {@link #reset} when a server starts (the store's world scope is known then). */
	public StaffModeManager(DataStore store, ConfigHandle config) {
		this.store = store;
		this.config = config;
	}

	/**
	 * Forgets everything in memory and loads the sessions of the store's current scope. Called when a
	 * server starts and stops, so an integrated server never sees another world's sessions.
	 */
	public void reset(boolean load) {
		sessions.clear();
		waitingForConfirmation.clear();
		if (!load) {
			return;
		}
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

	private static boolean isRestored(JsonObject session) {
		return state(session) == SessionState.RESTORING && session.has(RESTORED);
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
		// Staff mode replaces this inventory: nobody may keep editing it (each menu audits its changes on closing).
		Inspect.closeEditMenusOn(player);
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
		applyStaffState(player, mode);
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
		// Already restored by an earlier attempt that failed later: only finish, never hand out the items again.
		boolean restored = isRestored(session);
		List<String> problems = List.of();
		if (!restored) {
			boolean intact = StaffSnapshot.hash(snapshot).equals(session.get("hash").getAsString())
					&& StaffSnapshot.itemsDecode(snapshot, server.registryAccess());
			problems = RestoreCheck.problems(intact, StaffSnapshot.level(server, snapshot).isPresent(), StaffSnapshot.sameGameVersion(snapshot));
		}
		if (!problems.isEmpty() && !confirmed) {
			waitingForConfirmation.put(id, problems);
			return ExitResult.NEEDS_CONFIRMATION;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("session", session.get("session").getAsString());
		if (restored) {
			details.put("note", "survival state had already been restored; session finished only");
		} else if (confirmed) {
			details.put("confirmed_problems", String.join("; ", problems));
			details.put("replaced_inventory", describeInventory(player));
		}
		try {
			completeRestore(player, session, actor, "restored");
		} catch (IOException | RuntimeException e) {
			MejtaUtils.log().error("Restoring the staff-mode state of {} failed", player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal(isRestored(session)
					? "Your survival state is back, but staff mode could not be closed. Run /staffmode off again (nothing is restored twice) or ask an admin."
					: "Restoring failed; your saved state is kept. Try /staffmode off again or ask an admin.").withStyle(ChatFormatting.RED));
			details.put("restored", isRestored(session));
			UtilsAudit.record("staffmode.exit", actor, id, player.getGameProfile().name(), AuditOutcome.FAILED, details, UtilsPermissions.STAFFMODE);
			return ExitResult.FAILED;
		}
		waitingForConfirmation.remove(id);
		player.sendSystemMessage(Component.literal("Staff mode off. Your survival state is back.").withStyle(ChatFormatting.AQUA));
		UtilsAudit.record("staffmode.exit", actor, id, player.getGameProfile().name(), AuditOutcome.SUCCESS, details, UtilsPermissions.STAFFMODE);
		return ExitResult.DONE;
	}

	/**
	 * Restoration, each step written before the next: {@code RESTORING} on disk; the survival state back
	 * and in the player's save; the {@code restored} marker on disk; the position; vanish off; the player
	 * saved again; the session moved to the history. A session that already has the marker skips straight
	 * to the position (if not yet done) and the rest. Call only once the player has joined.
	 */
	private void completeRestore(ServerPlayer player, JsonObject session, AuditActor actor, String outcome) throws IOException {
		UUID id = player.getUUID();
		MinecraftServer server = player.level().getServer();
		JsonObject snapshot = session.getAsJsonObject("snapshot");
		if (state(session) != SessionState.RESTORING) {
			session.addProperty("state", SessionState.RESTORING.name());
			write(id, session);
		}
		if (!session.has(RESTORED)) {
			StaffSnapshot.restore(player, snapshot);
			PlayerSaves.save(player); // the survival inventory is in the save before the marker says it is back
			// Set before the write: if the write fails, retries in this run still see it and never restore twice.
			session.addProperty(RESTORED, Instant.now().toString());
			write(id, session);
		}
		if (!session.has(POSITION_RESTORED)) {
			session.addProperty(POSITION_RESTORED, true); // tried once; a failure is reported, not repeated
			try {
				StaffSnapshot.restorePosition(player, snapshot, server.overworld());
			} catch (RuntimeException e) {
				MejtaUtils.log().error("Could not teleport {} back to their saved position", player.getGameProfile().name(), e);
				player.sendSystemMessage(Component.literal("You could not be moved back to where you entered staff mode.")
						.withStyle(ChatFormatting.YELLOW));
			}
		}
		if (session.get("vanished_by_staffmode").getAsBoolean()) {
			MejtaUtils.vanish().setVanished(player, false, actor);
		}
		PlayerSaves.save(player); // the restored state is in the player's save before the session ends
		finish(id, session, outcome);
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
		SessionState.JoinAction action = state(session).onJoin(intact, StaffSnapshot.inventoryMatches(player, snapshot), onlyTools(player),
				isRestored(session));
		AuditActor system = AuditActor.system(MejtaUtils.ID);
		if (action == SessionState.JoinAction.REPEAT_RESTORE || action == SessionState.JoinAction.FINISH_RESTORE) {
			// One tick after the join: the restored position may be in another dimension, and changing it
			// during login is not safe. Until then the session stays RESTORING (staff-mode rules apply).
			MejtaCoreAPI.scheduler().runLater(1, () -> recoverRestoring(player, session, action));
			return;
		}
		try {
			switch (action) {
				case RESUME -> {
					session.addProperty("state", SessionState.ACTIVE.name());
					write(id, session);
					if (session.get("vanished_by_staffmode").getAsBoolean() && !MejtaUtils.vanish().isHidden(id)) {
						MejtaUtils.vanish().setVanished(player, true, system);
					}
					GameType mode = config.get(UtilsSettings.STAFF_GAMEMODE) == UtilsSettings.StaffGameMode.CREATIVE ? GameType.CREATIVE : GameType.ADVENTURE;
					applyStaffState(player, mode);
					player.sendSystemMessage(Component.literal("You are still in staff mode. /staffmode off restores your survival state.")
							.withStyle(ChatFormatting.AQUA));
				}
				case REPEAT_RESTORE, FINISH_RESTORE -> {
					// handled by recoverRestoring
				}
				case CANCEL_ENTRY -> {
					// The inventory is the saved one already; the rest (XP, game mode, abilities...) may have been
					// switched to staff mode's. Replacing with the snapshot changes no item and brings it back.
					StaffSnapshot.restore(player, snapshot);
					PlayerSaves.save(player);
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

	/** A session left in {@code RESTORING}: restored (or, with the marker, only finished) one tick after the join. */
	private void recoverRestoring(ServerPlayer player, JsonObject session, SessionState.JoinAction action) {
		UUID id = player.getUUID();
		if (player.hasDisconnected() || player.isRemoved() || player.level().getServer().getPlayerList().getPlayer(id) != player
				|| sessions.get(id) != session) {
			return; // left again (the next join retries), or the session changed meanwhile
		}
		AuditActor system = AuditActor.system(MejtaUtils.ID);
		AuditOutcome outcome = AuditOutcome.SUCCESS;
		try {
			completeRestore(player, session, system, action == SessionState.JoinAction.FINISH_RESTORE
					? "finished after restart (already restored)" : "restored after restart");
			player.sendSystemMessage(Component.literal("Staff mode had been ending when the server stopped; your survival state is back.")
					.withStyle(ChatFormatting.AQUA));
		} catch (IOException | RuntimeException e) {
			outcome = AuditOutcome.FAILED;
			MejtaUtils.log().error("Staff-mode recovery of {} failed", player.getGameProfile().name(), e);
			player.sendSystemMessage(Component.literal("Restoring your survival state failed; it is kept. Try /staffmode off or ask an admin.")
					.withStyle(ChatFormatting.RED));
		}
		UtilsAudit.record("staffmode.recover", system, id, player.getGameProfile().name(), outcome,
				Map.of("state", SessionState.RESTORING.name(), "action", action.name()), UtilsPermissions.STAFFMODE);
	}

	/**
	 * A staff-mode player is about to die (from Fabric's {@code ALLOW_DEATH}, before any drop): the
	 * staff-mode inventory (tools, items taken in creative) and XP are cleared, so nothing drops. The
	 * survival state is safe in the snapshot. Only for an active session whose inventory is known to be the
	 * staff-mode one; a session being restored holds the real inventory, which then drops as usual.
	 */
	public void onFatalDamage(ServerPlayer player) {
		UUID id = player.getUUID();
		JsonObject session = sessions.get(id);
		if (session == null || state(session) != SessionState.ACTIVE || waitingForConfirmation.containsKey(id)) {
			return;
		}
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			player.getInventory().setItem(i, ItemStack.EMPTY);
		}
		player.experienceLevel = 0;
		player.experienceProgress = 0.0f;
		player.totalExperience = 0;
	}

	/** After respawning in staff mode (e.g. /kill), the tools are given again. */
	public void onRespawn(ServerPlayer player) {
		if (isActive(player.getUUID())) {
			GameType mode = config.get(UtilsSettings.STAFF_GAMEMODE) == UtilsSettings.StaffGameMode.CREATIVE ? GameType.CREATIVE : GameType.ADVENTURE;
			applyStaffState(player, mode);
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

	/**
	 * Staff kit, game mode and abilities. Clearing effects also drops vanish's night vision and flight speed,
	 * so a vanished player gets them back.
	 */
	private void applyStaffState(ServerPlayer player, GameType mode) {
		StaffSnapshot.applyStaffState(player, mode, config.get(UtilsSettings.STAFF_FLIGHT));
		if (MejtaUtils.vanish().isHidden(player.getUUID())) {
			MejtaUtils.vanish().settingsChanged(player);
		}
	}
}
