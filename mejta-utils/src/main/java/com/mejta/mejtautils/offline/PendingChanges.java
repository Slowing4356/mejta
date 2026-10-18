package com.mejta.mejtautils.offline;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.offline.PendingChange.SlotRef;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.PlayerSaves;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * Stores queued offline changes ({@code pending/<target uuid>}, one per player) and applies them when
 * the player joins. All methods run on the server thread.
 *
 * <p><b>Order of writes.</b> The game saves a player's inventory only now and then (autosave, logout),
 * MejtaUtils' data at once. Every transfer is ordered so that a crash between two steps can never
 * duplicate an item:
 * <ul>
 * <li>staff member to queued change: take the item, save the players, then write the change;</li>
 * <li>change or claim to staff member: write the change first, then give the item and save the players;</li>
 * <li>change to the joining player: mark it {@code APPLYING}, apply, save the player, mark it {@code APPLIED}.
 * This runs once the player is in the player list (Fabric's {@code ServerPlayerEvents.JOIN}): earlier,
 * saving the players would skip them while the change is already recorded as applied.
 * After a crash, {@link PendingChange#recover} tells from the live inventory which side of the change
 * the save is on.</li>
 * </ul>
 * In the milliseconds between two of those steps a crash can lose (never duplicate) the one stack being
 * moved; its audit entry, written first, records it.
 */
public final class PendingChanges {
	static final String FOLDER = "pending";
	static final String BACKUPS = "backups";
	public static final String CHANNEL = MejtaUtils.ID + ".invsee";

	private final DataStore store;

	public PendingChanges(DataStore store) {
		this.store = store;
	}

	private static String key(UUID target) {
		return DataStore.playerKey(FOLDER, target);
	}

	public Optional<PendingChange> get(UUID target) {
		try {
			return store.read(key(target)).map(PendingChange::fromJson);
		} catch (IllegalArgumentException e) {
			MejtaUtils.log().error("Queued offline change for {} is unreadable and is ignored: {}", target, e.getMessage());
			return Optional.empty();
		}
	}

	/** Durable write. A change with nothing left to do is deleted. */
	public void save(PendingChange change) throws IOException {
		if (change.isDone() || (change.state() == PendingChange.State.OPEN && change.isEmpty())) {
			store.delete(key(change.target()));
			store.flush().join();
		} else {
			store.writeNow(key(change.target()), change.toJson());
		}
	}

	/** Every stored change (for claims and the admin list). */
	public List<PendingChange> all() {
		List<PendingChange> out = new ArrayList<>();
		for (String key : store.keys(FOLDER)) {
			store.read(key).ifPresent(json -> {
				try {
					out.add(PendingChange.fromJson(json));
				} catch (IllegalArgumentException e) {
					MejtaUtils.log().error("Ignoring unreadable queued change {}: {}", key, e.getMessage());
				}
			});
		}
		return out;
	}

	/** Changes with items waiting for {@code staff}. */
	public List<PendingChange> claimsOf(UUID staff) {
		List<PendingChange> out = new ArrayList<>();
		for (PendingChange change : all()) {
			if (change.staff().equals(staff) && !change.claims().isEmpty()) {
				out.add(change);
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- applying on join

	/**
	 * Called on the server thread when {@code player} has joined (they are in the player list, before their
	 * first tick), after any offline menu of theirs was closed.
	 */
	public void onJoin(ServerPlayer player) {
		Optional<PendingChange> found = get(player.getUUID());
		if (found.isEmpty()) {
			return;
		}
		PendingChange change = found.get();
		MinecraftServer server = player.level().getServer();
		HolderLookup.Provider registries = server.registryAccess();
		try {
			switch (change.state()) {
				case OPEN -> {
					PendingChange.Plan plan = change.plan(ref -> live(player, ref), (stack, json) -> ItemJson.matches(registries, stack, json));
					if (!plan.applicable()) {
						change.markCancelled("the player's saved inventory changed after the edit (" + plan.conflicts().size() + " slot(s))");
						save(change);
						report(change, AuditOutcome.CONFLICT, Severity.WARNING, "Queued change to " + player.getGameProfile().name()
								+ " was not applied: their inventory changed. Added items were returned to " + change.staffName() + "'s claims.");
						return;
					}
					backup(player);
					change.markApplying();
					save(change);
					apply(player, plan, registries);
				}
				case APPLYING -> {
					PendingChange.Recovery recovery = change.recover(ref -> live(player, ref), (stack, json) -> ItemJson.matches(registries, stack, json));
					switch (recovery) {
						case ALREADY_APPLIED -> {
							change.markApplied(removedClaims(change));
							save(change);
							report(change, AuditOutcome.SUCCESS, Severity.INFO, "Queued change to " + player.getGameProfile().name()
									+ " had been applied before a restart; recorded as applied.");
						}
						case NOT_APPLIED -> apply(player, change.plan(ref -> live(player, ref),
								(stack, json) -> ItemJson.matches(registries, stack, json)), registries);
						case UNKNOWN -> report(change, AuditOutcome.CONFLICT, Severity.CRITICAL, "Queued change to "
								+ player.getGameProfile().name() + " was interrupted and the inventory matches neither before nor after it. "
								+ "Nothing was changed; check /mejtaadmin mejtautils pending.");
					}
					return;
				}
				default -> {
					return; // APPLIED or CANCELLED: only claims remain, which belong to the staff member
				}
			}
			PendingChange done = get(player.getUUID()).orElse(change);
			report(done, AuditOutcome.SUCCESS, Severity.INFO, "Queued change to " + player.getGameProfile().name() + " applied ("
					+ done.ops().size() + " slot(s))" + (done.claims().isEmpty() ? "" : "; " + done.claims().size()
					+ " item(s) for " + done.staffName() + " to collect with /invsee claims"));
		} catch (IOException | RuntimeException e) {
			MejtaUtils.log().error("Could not apply queued change for {}", player.getGameProfile().name(), e);
			report(change, AuditOutcome.FAILED, Severity.CRITICAL, "Queued change to " + player.getGameProfile().name()
					+ " failed: " + e.getClass().getSimpleName() + ". See the server log.");
		}
	}

	private void apply(ServerPlayer player, PendingChange.Plan plan, HolderLookup.Provider registries) throws IOException {
		PendingChange change = get(player.getUUID()).orElseThrow();
		if (!plan.applicable()) {
			change.markCancelled("the player's inventory changed");
			save(change);
			return;
		}
		for (Map.Entry<SlotRef, JsonElement> write : plan.writes().entrySet()) {
			ItemStack stack = write.getValue() == null ? ItemStack.EMPTY
					: ItemJson.decode(registries, write.getValue()).orElseThrow(() -> new IllegalStateException("added item no longer decodes"));
			container(player, write.getKey().container()).setItem(write.getKey().slot(), stack);
		}
		// Their save holds the result before the change is recorded as applied. If it cannot be written,
		// the change stays APPLYING and the next join tells from the inventory which side it is on.
		PlayerSaves.save(player);
		change.markApplied(plan.toClaims());
		save(change);
	}

	private static List<JsonElement> removedClaims(PendingChange change) {
		List<JsonElement> out = new ArrayList<>();
		if (!change.deleteRemoved()) {
			for (PendingChange.SlotOp op : change.ops()) {
				if (op.kind() == PendingChange.Kind.REMOVE) {
					out.add(op.item());
				}
			}
		}
		return out;
	}

	private static ItemStack live(ServerPlayer player, SlotRef ref) {
		ItemStack stack = container(player, ref.container()).getItem(ref.slot());
		return stack.isEmpty() ? null : stack;
	}

	static Container container(ServerPlayer player, PendingChange.Container kind) {
		return kind == PendingChange.Container.INVENTORY ? player.getInventory() : player.getEnderChestInventory();
	}

	/** Keeps the last {@code invsee.offline.backups} inventories of the player before a change is applied. */
	private void backup(ServerPlayer player) throws IOException {
		HolderLookup.Provider registries = player.level().getServer().registryAccess();
		JsonObject snapshot = new JsonObject();
		snapshot.addProperty("time", Instant.now().toString());
		snapshot.add("inventory", encode(registries, player.getInventory()));
		snapshot.add("ender_chest", encode(registries, player.getEnderChestInventory()));
		String key = DataStore.playerKey(BACKUPS, player.getUUID());
		JsonObject doc = store.read(key).orElseGet(JsonObject::new);
		JsonArray list = doc.has("snapshots") ? doc.getAsJsonArray("snapshots") : new JsonArray();
		list.add(snapshot);
		int keep = MejtaUtils.config().get(UtilsSettings.OFFLINE_BACKUPS);
		while (list.size() > keep) {
			list.remove(0);
		}
		doc.add("snapshots", list);
		store.writeNow(key, doc);
	}

	private static JsonObject encode(HolderLookup.Provider registries, Container container) {
		JsonObject slots = new JsonObject();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				slots.add(String.valueOf(i), ItemJson.encode(registries, stack));
			}
		}
		return slots;
	}

	// ---------------------------------------------------------------- claims and cancelling

	/**
	 * Gives claim {@code index} of {@code change} to {@code staff}: written durably first, then given.
	 *
	 * @return {@code false} if it does not fit in their inventory, does not decode, or the staff member is in
	 *         staff mode (nothing changed then)
	 */
	public boolean claim(ServerPlayer staff, PendingChange change, int index) throws IOException {
		HolderLookup.Provider registries = staff.level().getServer().registryAccess();
		JsonElement json = change.claims().get(index);
		Optional<ItemStack> decoded = ItemJson.decode(registries, json);
		if (decoded.isEmpty()) {
			staff.sendSystemMessage(Component.literal("That item no longer exists in this game version and cannot be given."));
			return false;
		}
		ItemStack stack = decoded.get();
		if (MejtaUtils.staffMode().isActive(staff.getUUID())) {
			// The staff-mode inventory is replaced on exit: a claimed item would be destroyed.
			staff.sendSystemMessage(Component.literal("Leave staff mode first: items claimed now would be lost when it ends."));
			UtilsAudit.record("invsee.claim", MejtaCoreAPI.actor(staff), change.target(), change.targetName(), AuditOutcome.DENIED,
					Map.of("reason", "in staff mode"), null);
			return false;
		}
		if (staff.getInventory().getFreeSlot() < 0) {
			return false;
		}
		UtilsAudit.record("invsee.claim", MejtaCoreAPI.actor(staff), change.target(), change.targetName(), AuditOutcome.SUCCESS,
				Map.of("item", ItemJson.describe(stack)), null);
		change.takeClaim(index);
		save(change);
		staff.getInventory().add(stack);
		if (!stack.isEmpty()) {
			staff.drop(stack, false); // cannot happen with a free slot, but never lose it
		}
		staff.level().getServer().getPlayerList().saveAll();
		return true;
	}

	/**
	 * Cancels an open change: added items return to the staff member's claims. A change interrupted while
	 * {@code APPLYING} cannot be cancelled this way (its items may already be in the player's inventory);
	 * use {@link #discard} after checking the player by hand.
	 */
	public boolean cancel(UUID target, AuditActor actor, String reason) throws IOException {
		Optional<PendingChange> found = get(target);
		if (found.isEmpty() || found.get().state() != PendingChange.State.OPEN) {
			return false;
		}
		PendingChange change = found.get();
		change.markCancelled(reason);
		save(change);
		UtilsAudit.record("invsee.offline.cancel", actor, change.target(), change.targetName(), AuditOutcome.SUCCESS,
				Map.of("reason", reason, "returned_items", change.claims().size()), UtilsPermissions.INVSEE_OFFLINE);
		return true;
	}

	/**
	 * Deletes the record of a change in any state, without giving or taking any item. For a change stuck
	 * in {@code APPLYING}: an admin checks the player's inventory (and the backups) by hand first.
	 */
	public boolean discard(UUID target, AuditActor actor) throws IOException {
		Optional<PendingChange> found = get(target);
		if (found.isEmpty()) {
			return false;
		}
		PendingChange change = found.get();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("state", change.state().name());
		details.put("slots", change.ops().size());
		details.put("unclaimed_items", change.claims().size());
		details.put("data", change.toJson().toString());
		UtilsAudit.record("invsee.offline.discard", actor, change.target(), change.targetName(), AuditOutcome.SUCCESS, details,
				UtilsPermissions.ADMIN);
		store.delete(key(target));
		store.flush().join();
		return true;
	}

	private static void report(PendingChange change, AuditOutcome outcome, Severity severity, String message) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("staff", change.staffName());
		details.put("slots", change.ops().size());
		details.put("state", change.state().name());
		details.put("delete_removed", change.deleteRemoved());
		details.put("message", message);
		UtilsAudit.record("invsee.offline.apply", AuditActor.system(MejtaUtils.ID), change.target(), change.targetName(), outcome, details, null);
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaUtils.ID, CHANNEL)
				.severity(severity)
				.title("Offline inventory change: " + change.targetName())
				.message(message)
				.field("player", change.targetName())
				.field("uuid", change.target())
				.field("staff", change.staffName())
				.field("outcome", outcome.name())
				.inGameAudience(UtilsPermissions.INVSEE_OFFLINE)
				.build());
	}
}
