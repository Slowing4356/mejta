package com.mejta.mejtacorpse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacorpse.data.Access;
import com.mejta.mejtacorpse.data.BackupStore;
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.data.CorpseStore;
import com.mejta.mejtacorpse.data.DeathBackup;
import com.mejta.mejtacorpse.data.SlotLayout;
import com.mejta.mejtacorpse.menu.CorpseMenu;
import com.mejta.mejtacorpse.mixin.PlayerListInvoker;
import com.mejta.mejtacorpse.menu.LiveCorpse;
import com.mejta.mejtacorpse.util.ItemJson;
import com.mejta.mejtacorpse.world.CorpseEntities;

/**
 * Creates, opens, repairs and removes corpses. Server thread only.
 *
 * <p><b>Death without duplication.</b> When a player dies with items:
 * <ol>
 * <li>the corpse is written to disk as {@link CorpseRecord.State#PENDING}, holding the items;</li>
 * <li>the items are removed from the player's inventory and <em>the player is saved</em>;</li>
 * <li>the corpse is written again as {@link CorpseRecord.State#ACTIVE}; only now can it be opened.</li>
 * </ol>
 * If the server stops between 1 and 3, the corpse stays pending. When its owner next joins, their
 * inventory shows which side was saved: if they still hold the items, the death was never saved and the
 * corpse is deleted; otherwise the corpse becomes active. The items are never in both places.
 */
public final class CorpseManager {
	/** Writes a player's save file; throws {@link IllegalStateException} if that is impossible. */
	@FunctionalInterface
	public interface PlayerSaver {
		void save(ServerPlayer player);
	}

	/**
	 * The game's own save of the dying player only (as {@code saveAll} saves each player), so a death
	 * does not write every online player's files. The player must be in the player list, which a dying
	 * player always is.
	 */
	public static final PlayerSaver SAVE_PLAYER = player -> {
		PlayerList list = player.level().getServer().getPlayerList();
		if (list.getPlayer(player.getUUID()) != player) {
			throw new IllegalStateException(player.getGameProfile().name() + " is not in the player list");
		}
		((PlayerListInvoker) list).mejtacorpse$save(player);
	};

	static final int BUTTON_INFO = 45;
	static final int BUTTON_TAKE_ALL = 49;
	static final int BUTTON_CLOSE = 53;

	private final ConfigHandle config;
	private final CorpseStore store;
	private final CorpseBackups backups;
	private final Map<String, LiveCorpse> live = new HashMap<>();
	/** Corpse entities MejtaCorpse itself removes: their removal must not trigger a repair. */
	private final Set<UUID> ownRemovals = ConcurrentHashMap.newKeySet();
	private final Set<String> repairs = ConcurrentHashMap.newKeySet();
	private volatile MinecraftServer server;

	public CorpseManager(ConfigHandle config, CorpseStore store, BackupStore backupStore) {
		this.config = config;
		this.store = store;
		this.backups = new CorpseBackups(config, backupStore, store);
	}

	public CorpseStore store() {
		return store;
	}

	public CorpseBackups backups() {
		return backups;
	}

	// ---------------------------------------------------------------- death

	/**
	 * Moves the dying player's items into a new corpse. Returns false if there was nothing to move or it
	 * could not be done safely; the game then drops whatever is left, as in vanilla.
	 */
	public boolean capture(ServerPlayer player, PlayerSaver saver) {
		if (!config.get(CorpseSettings.ENABLED)) {
			return false;
		}
		MinecraftServer srv = player.level().getServer();
		HolderLookup.Provider registries = srv.registryAccess();
		Inventory inventory = player.getInventory();
		Map<Integer, JsonElement> items = new TreeMap<>();
		int size = Math.min(inventory.getContainerSize(), SlotLayout.MAX_INVENTORY_SLOTS);
		for (int slot = 0; slot < size; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			try {
				items.put(slot, ItemJson.encode(registries, stack));
			} catch (RuntimeException e) {
				// Left in the inventory: it drops as in vanilla.
				MejtaCorpse.log().warn("Could not store {} in {}'s corpse; it drops instead", ItemJson.describe(stack),
						player.getGameProfile().name(), e);
			}
		}
		if (items.isEmpty()) {
			return false;
		}
		ServerLevel level = player.level();
		boolean remote = CorpseEntities.overVoid(level, player.getX(), player.getY(), player.getZ());
		CorpseRecord pending = CorpseRecord.pending(store.newId(), player.getUUID(), player.getGameProfile().name(),
				System.currentTimeMillis(), level.dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
				player.getYRot(), remote, items, CorpseEntities.skinOf(player));

		// 1. The corpse with the items, on disk.
		try {
			store.saveNow(pending);
		} catch (IOException e) {
			MejtaCorpse.log().error("Could not save {}'s corpse; their items drop instead", player.getGameProfile().name(), e);
			return false;
		}
		// 2. The items out of the inventory, and the player's save without them.
		Map<Integer, ItemStack> removed = new HashMap<>();
		for (int slot : items.keySet()) {
			removed.put(slot, inventory.getItem(slot));
			inventory.setItem(slot, ItemStack.EMPTY);
		}
		try {
			saver.save(player);
		} catch (RuntimeException e) {
			removed.forEach(inventory::setItem);
			store.delete(pending.id());
			MejtaCorpse.log().error("Could not save {} after moving their items; the items drop instead", player.getGameProfile().name(), e);
			return false;
		}
		// 3. Active.
		activate(srv, pending);
		if (config.get(CorpseSettings.DEATH_MESSAGE)) {
			player.sendSystemMessage(describe(store.get(pending.id()).orElse(pending), true, mayOpenRemotely(player, pending)));
		}
		return true;
	}

	private void activate(MinecraftServer srv, CorpseRecord pending) {
		CorpseRecord active = pending.withState(CorpseRecord.State.ACTIVE);
		try {
			store.saveNow(active);
		} catch (IOException e) {
			MejtaCorpse.log().error("Could not activate corpse {}; it is completed when {} next joins", pending.id(), pending.ownerName(), e);
			return;
		}
		backups.take(active);
		spawnEntities(srv, active);
	}

	private void spawnEntities(MinecraftServer srv, CorpseRecord corpse) {
		Optional<CorpseRecord> spawned = CorpseEntities.spawn(srv, corpse, config.get(CorpseSettings.SHOW_SKIN), config.get(CorpseSettings.SHOW_NAME),
				config.get(CorpseSettings.SHOW_ARMOR));
		if (spawned.isPresent()) {
			// Only the entities change: the items are taken from the stored record as it is now.
			store.get(corpse.id()).ifPresent(current -> store.saveLater(current.withEntities(spawned.get().body(), spawned.get().hitbox())));
		} else {
			MejtaCorpse.log().warn("Could not place the entities of corpse {} in {}; it can still be opened with /corpse open {}",
					corpse.id(), corpse.dimension(), corpse.id());
		}
	}

	/** On join: finishes the owner's corpses that a stop interrupted (see the class description). */
	public void onJoin(ServerPlayer player) {
		HolderLookup.Provider registries = player.level().getServer().registryAccess();
		Inventory inventory = player.getInventory();
		for (CorpseRecord corpse : store.ofOwner(player.getUUID())) {
			if (corpse.state() != CorpseRecord.State.PENDING) {
				continue;
			}
			boolean stillHeld = corpse.items().entrySet().stream().allMatch(e -> e.getKey() < inventory.getContainerSize()
					&& ItemJson.matches(registries, inventory.getItem(e.getKey()), e.getValue()));
			if (stillHeld) {
				store.delete(corpse.id());
				MejtaCorpse.log().info("Discarded unfinished corpse {}: {} still has its items (their death was not saved)", corpse.id(),
						corpse.ownerName());
			} else {
				activate(player.level().getServer(), corpse);
				MejtaCorpse.log().info("Completed corpse {} of {}", corpse.id(), corpse.ownerName());
			}
		}
		backups.pruneOld(player.getUUID());
	}

	// ---------------------------------------------------------------- opening

	private boolean mayOpenRemotely(ServerPlayer player, CorpseRecord corpse) {
		if (MejtaCoreAPI.hasPermission(player, CorpsePermissions.ADMIN)) {
			return true;
		}
		return corpse.owner().equals(player.getUUID()) && MejtaCoreAPI.hasPermission(player, CorpsePermissions.USE)
				&& (corpse.remote() || config.get(CorpseSettings.OWNER_REMOTE_OPEN));
	}

	/** A right-click on a corpse entity. */
	public InteractionResult onUse(ServerPlayer player, Entity entity, InteractionHand hand) {
		Optional<CorpseRecord> corpse = store.ofEntity(entity.getUUID());
		if (corpse.isEmpty()) {
			if (store.takeRetired(entity.getUUID())) {
				removeEntity(entity);
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.SUCCESS;
		}
		if (player.containerMenu instanceof CorpseMenu menu && menu.corpse().record().id().equals(corpse.get().id())) {
			return InteractionResult.SUCCESS;
		}
		open(player, corpse.get(), false);
		return InteractionResult.SUCCESS;
	}

	/** Whether the entity shows a corpse (and so cannot be hit). */
	public boolean isCorpseEntity(Entity entity) {
		return store.ofEntity(entity.getUUID()).isPresent();
	}

	/**
	 * Opens the corpse's menu if the player may. {@code remote}: from a command, without being near it.
	 * Returns whether it opened.
	 */
	public boolean open(ServerPlayer viewer, CorpseRecord corpse, boolean remote) {
		if (corpse.state() != CorpseRecord.State.ACTIVE) {
			viewer.sendSystemMessage(Component.literal("This corpse is not ready yet.").withStyle(ChatFormatting.RED));
			return false;
		}
		long now = System.currentTimeMillis();
		int publicAfter = config.get(CorpseSettings.PUBLIC_AFTER_MINUTES);
		Access.Decision decision = Access.decide(viewer.getUUID(), corpse, MejtaCoreAPI.hasPermission(viewer, CorpsePermissions.OPEN_ANY),
				publicAfter, now);
		if (decision == Access.Decision.OWNER && !MejtaCoreAPI.hasPermission(viewer, CorpsePermissions.USE)) {
			decision = Access.Decision.DENIED;
		}
		if (decision == Access.Decision.DENIED) {
			long wait = Access.untilPublic(corpse, publicAfter, now);
			viewer.sendSystemMessage(Component.literal("This is " + corpse.ownerName() + "'s corpse." + (wait > 0
					? " Anyone may open it in " + minutes(wait) + "." : "")).withStyle(ChatFormatting.RED));
			return false;
		}
		if (remote && !mayOpenRemotely(viewer, corpse)) {
			viewer.sendSystemMessage(Component.literal("Go to the corpse to open it.").withStyle(ChatFormatting.RED));
			return false;
		}
		LiveCorpse lc = live.get(corpse.id());
		if (lc == null) {
			lc = new LiveCorpse(store, viewer.level().getServer().registryAccess(), corpse);
			setIcons(lc);
			live.put(corpse.id(), lc);
		}
		if (lc.broken()) {
			viewer.sendSystemMessage(Component.literal("This corpse is locked after a save error. Staff have been told.").withStyle(ChatFormatting.RED));
			return false;
		}
		LiveCorpse opened = lc;
		viewer.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
			opened.addViewer();
			return new CorpseMenu(id, inventory, opened, remote);
		}, Component.literal(corpse.ownerName() + "'s corpse")));
		if (decision == Access.Decision.STAFF) {
			audit("corpse.open", viewer, corpse, Map.of("remote", remote));
		}
		return true;
	}

	private void setIcons(LiveCorpse lc) {
		CorpseRecord r = lc.record();
		ItemStack filler = MenuIcons.of(Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "));
		for (int slot = SlotLayout.SIZE; slot < LiveCorpse.SLOTS; slot++) {
			lc.setIcon(slot, filler.copy());
		}
		lc.setIcon(BUTTON_INFO, MenuIcons.of(Items.PAPER, Component.literal(r.ownerName() + "'s corpse").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Died at " + where(r)),
				MenuIcons.line(minutes(System.currentTimeMillis() - r.createdMillis()) + " ago"),
				MenuIcons.line("ID " + r.id())));
		lc.setIcon(BUTTON_TAKE_ALL, MenuIcons.of(Items.CHEST, Component.literal("Take all").withStyle(ChatFormatting.GREEN),
				MenuIcons.line("Moves every item into your inventory."),
				MenuIcons.line("The owner gets each item back in its old slot.")));
		lc.setIcon(BUTTON_CLOSE, MenuIcons.of(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED)));
	}

	public void buttonClicked(CorpseMenu menu, int slot, Player player) {
		if (slot == BUTTON_TAKE_ALL) {
			menu.takeAll(player, menu.corpse().record().owner().equals(player.getUUID()));
			if (menu.corpse().holdsNothing() && player instanceof ServerPlayer sp) {
				sp.closeContainer();
			}
		} else if (slot == BUTTON_CLOSE && player instanceof ServerPlayer sp) {
			sp.closeContainer();
		}
	}

	/** After items moved: the body shows the armour the corpse holds now. */
	public void itemsChanged(LiveCorpse lc) {
		MinecraftServer srv = server;
		if (srv == null) {
			return;
		}
		store.get(lc.record().id()).ifPresent(corpse -> CorpseEntities.find(srv, corpse, corpse.body())
				.filter(e -> e instanceof LivingEntity)
				.ifPresent(e -> CorpseEntities.dress((LivingEntity) e, corpse, srv.registryAccess(), config.get(CorpseSettings.SHOW_ARMOR))));
	}

	public boolean stillValid(CorpseMenu menu, Player player) {
		LiveCorpse lc = menu.corpse();
		CorpseRecord r = lc.record();
		if (lc.broken() || store.get(r.id()).isEmpty()) {
			return false;
		}
		if (menu.remote()) {
			return true;
		}
		int max = config.get(CorpseSettings.MAX_DISTANCE);
		return player.level().dimension().identifier().toString().equals(r.dimension())
				&& player.distanceToSqr(r.x(), Math.max(r.y(), player.level().getMinY() + 1), r.z()) <= (double) max * max;
	}

	public void closed(CorpseMenu menu, Player player) {
		LiveCorpse lc = menu.corpse();
		CorpseRecord r = lc.record();
		boolean owner = r.owner().equals(player.getUUID());
		if (menu.taken() > 0) {
			backups.record(r, owner ? DeathBackup.Kind.TAKEN_BY_OWNER : DeathBackup.Kind.TAKEN_BY_OTHER, player.getGameProfile().name(),
					menu.taken(), "");
		}
		if (menu.taken() > 0 && !owner && config.get(CorpseSettings.AUDIT_LOOTING) && player instanceof ServerPlayer sp) {
			audit("corpse.loot", sp, r, Map.of("items_taken", menu.taken()));
		}
		if (lc.removeViewer() > 0 || lc.broken()) {
			return; // a broken corpse stays loaded (and locked) until the server restarts
		}
		live.remove(r.id());
		if (lc.holdsNothing()) {
			finish(r);
		}
	}

	/** A save failed after items moved: lock the corpse and tell staff. */
	public void broke(LiveCorpse lc) {
		CorpseRecord r = lc.record();
		MejtaCorpse.log().error("Corpse {} of {} is locked: its items could not be saved", r.id(), r.ownerName());
		MinecraftServer srv = server;
		if (srv != null) {
			for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
				if (p.containerMenu instanceof CorpseMenu menu && menu.corpse() == lc) {
					p.closeContainer();
					p.sendSystemMessage(Component.literal("This corpse could not be saved and is locked. Staff have been told.")
							.withStyle(ChatFormatting.RED));
				}
			}
		}
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaCorpse.ID, "mejtacorpse.error")
				.severity(Severity.CRITICAL)
				.title("Corpse locked")
				.message("The corpse " + r.id() + " of " + r.ownerName() + " could not be saved and is locked until a restart. Check the disk and the server log.")
				.field("corpse", r.id())
				.inGameAudience(CorpsePermissions.OPEN_ANY)
				.build());
	}

	// ---------------------------------------------------------------- entities

	/** An entity was loaded: one left over from a removed or replaced corpse is removed. */
	public void onEntityLoad(Entity entity) {
		if (store.takeRetired(entity.getUUID())) {
			removeEntity(entity);
		}
	}

	/** A corpse entity was killed or removed by something else: put the corpse's entities back. */
	public void onEntityUnload(Entity entity) {
		if (ownRemovals.remove(entity.getUUID())) {
			return;
		}
		Entity.RemovalReason reason = entity.getRemovalReason();
		if (reason == null || !reason.shouldDestroy()) {
			return;
		}
		store.ofEntity(entity.getUUID()).filter(c -> c.state() == CorpseRecord.State.ACTIVE).ifPresent(c -> repairs.add(c.id()));
	}

	/** Removes the corpse entity if it is loaded, else remembers to remove it when it loads. */
	private void removeOrRetire(MinecraftServer srv, CorpseRecord corpse, UUID entity) {
		if (entity == null) {
			return;
		}
		Optional<Entity> loaded = CorpseEntities.find(srv, corpse, entity);
		if (loaded.isPresent()) {
			removeEntity(loaded.get());
		} else {
			store.retire(entity);
		}
	}

	private void removeEntity(Entity entity) {
		ownRemovals.add(entity.getUUID());
		entity.discard();
	}

	private void repair(MinecraftServer srv, String id) {
		Optional<CorpseRecord> corpse = store.get(id);
		if (corpse.isEmpty() || corpse.get().state() != CorpseRecord.State.ACTIVE) {
			return;
		}
		for (UUID old : new UUID[] {corpse.get().body(), corpse.get().hitbox()}) {
			removeOrRetire(srv, corpse.get(), old);
		}
		spawnEntities(srv, corpse.get().withEntities(null, null));
		MejtaCorpse.log().info("Put back the entities of corpse {} of {}", id, corpse.get().ownerName());
	}

	/** Removes an empty corpse: its record and its entities. */
	void finish(CorpseRecord stale) {
		CorpseRecord corpse = store.get(stale.id()).orElse(stale);
		live.remove(corpse.id());
		store.delete(corpse.id());
		MinecraftServer srv = server;
		if (srv != null) {
			for (UUID e : new UUID[] {corpse.body(), corpse.hitbox()}) {
				removeOrRetire(srv, corpse, e);
			}
		}
	}

	// ---------------------------------------------------------------- lifecycle

	public void serverStarted(MinecraftServer srv) {
		server = srv;
		List<String> damaged = store.load();
		for (String key : damaged) {
			MejtaCorpse.log().error("Damaged corpse record {} was skipped; it is kept on disk for inspection", key);
		}
		int spawned = 0;
		for (CorpseRecord corpse : store.all()) {
			if (corpse.state() == CorpseRecord.State.ACTIVE && corpse.body() == null) {
				spawnEntities(srv, corpse);
				spawned++;
			}
		}
		MejtaCorpse.log().info("{} corpse(s) loaded{}", store.size(), spawned > 0 ? ", entities placed for " + spawned : "");
	}

	public void serverStopped() {
		server = null;
		live.clear();
		ownRemovals.clear();
		repairs.clear();
		store.clear();
	}

	/** Every server tick: repairs; every 5 seconds: expiry. */
	public void tick(MinecraftServer srv) {
		if (!repairs.isEmpty()) {
			for (String id : List.copyOf(repairs)) {
				repairs.remove(id);
				repair(srv, id);
			}
		}
		if (srv.getTickCount() % 100 == 0) {
			expire(srv);
		}
	}

	private void expire(MinecraftServer srv) {
		int minutes = config.get(CorpseSettings.EXPIRE_MINUTES);
		if (minutes <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		for (CorpseRecord corpse : store.all()) {
			if (corpse.state() != CorpseRecord.State.ACTIVE || live.containsKey(corpse.id()) || !Access.expired(corpse, minutes, now)) {
				continue;
			}
			Optional<ServerLevel> level = CorpseEntities.level(srv, corpse);
			BlockPos pos = BlockPos.containing(corpse.x(), Math.max(corpse.y(), level.map(l -> l.getMinY() + 1).orElse(0)), corpse.z());
			if (level.isEmpty() || !level.get().isLoaded(pos)) {
				continue; // expires once someone is near enough for its chunk to be loaded
			}
			boolean drop = config.get(CorpseSettings.EXPIRE_ACTION) == CorpseSettings.ExpireAction.DROP_ITEMS;
			List<ItemStack> stacks = new ArrayList<>();
			for (JsonElement json : corpse.items().values()) {
				ItemJson.decode(srv.registryAccess(), json).filter(s -> !s.isEmpty()).ifPresent(stacks::add);
			}
			// Emptied on disk first: a crash after this loses the items instead of keeping them twice.
			try {
				store.saveNow(corpse.withItems(Map.of()));
			} catch (IOException e) {
				MejtaCorpse.log().error("Could not expire corpse {}", corpse.id(), e);
				continue;
			}
			if (drop) {
				for (ItemStack stack : stacks) {
					level.get().addFreshEntity(new ItemEntity(level.get(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
				}
			}
			finish(corpse);
			backups.record(corpse, DeathBackup.Kind.EXPIRED, "", stacks.stream().mapToInt(ItemStack::getCount).sum(), drop ? "dropped" : "deleted");
			MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCorpse.ID, "corpse.expire")
					.actor(AuditActor.system(MejtaCorpse.ID))
					.target(corpse.owner(), corpse.ownerName())
					.outcome(AuditOutcome.SUCCESS)
					.detail("corpse", corpse.id())
					.detail("action", drop ? "dropped" : "deleted")
					.detail("stacks", stacks.size())
					.build());
		}
	}

	// ---------------------------------------------------------------- text

	static String where(CorpseRecord r) {
		return where(r.dimension(), r.x(), r.y(), r.z());
	}

	static String where(String dimension, double x, double y, double z) {
		String dim = dimension.startsWith("minecraft:") ? dimension.substring(10) : dimension;
		return (long) Math.floor(x) + ", " + (long) Math.floor(y) + ", " + (long) Math.floor(z) + " (" + dim + ")";
	}

	static String minutes(long millis) {
		long m = millis / 60_000;
		if (m < 1) {
			return "less than a minute";
		}
		if (m < 120) {
			return m + " min";
		}
		return m / 60 + " h";
	}

	/** One line about a corpse, with an [Open] button where it can be opened from afar. */
	MutableComponent describe(CorpseRecord r, boolean justDied, boolean openButton) {
		int stacks = r.items().size();
		MutableComponent line = Component.literal(justDied ? "Your items are in your corpse at " : "#" + r.id() + "  ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(where(r)).withStyle(ChatFormatting.GOLD))
				.append(Component.literal(justDied ? "." : "  " + stacks + " stack(s), " + minutes(System.currentTimeMillis() - r.createdMillis()) + " ago")
						.withStyle(ChatFormatting.GRAY));
		if (r.state() == CorpseRecord.State.PENDING) {
			line.append(Component.literal(" (being prepared)").withStyle(ChatFormatting.DARK_GRAY));
		} else if (openButton) {
			line.append(Component.literal(" ")).append(Component.literal("[Open]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.RunCommand("/corpse open " + r.id()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("/corpse open " + r.id())))));
		}
		return line;
	}

	boolean mayOpenFromAfar(ServerPlayer player, CorpseRecord corpse) {
		return mayOpenRemotely(player, corpse);
	}

	private static void audit(String action, ServerPlayer actor, CorpseRecord corpse, Map<String, ?> details) {
		AuditEntry.Builder b = AuditEntry.builder(MejtaCorpse.ID, action)
				.actor(MejtaCoreAPI.actor(actor))
				.target(corpse.owner(), corpse.ownerName())
				.outcome(AuditOutcome.SUCCESS)
				.detail("corpse", corpse.id());
		details.forEach(b::detail);
		MejtaCoreAPI.audit().record(b.build());
	}

	public int liveCount() {
		return live.size();
	}
}
