package com.mejta.mejtautils.inspect;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.offline.PendingChange;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * An offline player's saved inventory or ender chest. Every slot is a display copy, so nothing can be
 * taken out of it. With queued editing on (experimental):
 * <ul>
 * <li>click an item: queue its removal (it stays in the save until the player joins); click again to undo;</li>
 * <li>shift-click an item in your own inventory: queue adding it (it leaves your inventory now);</li>
 * <li>the "removed items" button chooses between collecting them later ({@code /invsee claims}) and deleting them.</li>
 * </ul>
 * Every change is written to disk at once; the menu itself holds no state worth saving. When the player
 * joins, every offline menu of theirs is closed before the change is applied.
 */
public final class OfflineMenu extends InspectMenu {
	public enum View {
		INVENTORY,
		ENDER_CHEST
	}

	/** Open offline menus, so they can be closed when their player joins. Server thread only. */
	private static final Set<OfflineMenu> OPEN = new HashSet<>();

	private final OfflineSnapshot snapshot;
	private final View view;
	private final boolean mayQueue;
	private final SimpleContainer display;
	private final int infoStart;
	private Component notice = Component.empty();

	private OfflineMenu(int containerId, Inventory viewerInventory, ServerPlayer viewer, OfflineSnapshot snapshot, View view, boolean queue) {
		super(chestType(view == View.INVENTORY ? 6 : 4), containerId, viewer, view == View.INVENTORY ? 6 : 4, false);
		this.snapshot = snapshot;
		this.view = view;
		this.mayQueue = queue;
		this.display = new SimpleContainer(topSize);
		this.infoStart = view == View.INVENTORY ? LiveInventoryMenu.INFO_START : 27;
		for (int i = 0; i < topSize; i++) {
			addSlot(new LockedSlot(display, i));
		}
		addStandardInventorySlots(viewerInventory, 0, 0);
		refresh();
		OPEN.add(this);
	}

	/** @param queue whether the viewer asked for queued editing (permissions and the setting are checked here) */
	public static void open(ServerPlayer viewer, OfflineSnapshot snapshot, View view, boolean queue) {
		boolean mayQueue = queue && canQueue(viewer);
		String what = view == View.INVENTORY ? "inventory" : "ender chest";
		Component title = Component.literal(snapshot.profile().name() + "'s saved " + what + (mayQueue ? " (queue changes)" : " (offline)"));
		viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new OfflineMenu(id, inv, viewer, snapshot, view, mayQueue), title));
	}

	static boolean canQueue(ServerPlayer viewer) {
		return MejtaUtils.config().get(UtilsSettings.OFFLINE_EDIT)
				&& MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.INVSEE_OFFLINE_EDIT)
				&& !MejtaUtils.staffMode().isActive(viewer.getUUID());
	}

	/** Closes every offline menu showing {@code player}; called when that player joins. */
	public static void closeAllFor(UUID player) {
		for (OfflineMenu menu : List.copyOf(OPEN)) {
			if (menu.snapshot.profile().id().equals(player)) {
				menu.viewer.sendSystemMessage(Component.literal(menu.snapshot.profile().name()
						+ " joined; the offline view was closed. Use /invsee again for their live inventory.").withStyle(ChatFormatting.YELLOW));
				menu.close();
			}
		}
	}

	private UUID targetId() {
		return snapshot.profile().id();
	}

	private MinecraftServer server() {
		return viewer.level().getServer();
	}

	private PendingChange.Container container() {
		return view == View.INVENTORY ? PendingChange.Container.INVENTORY : PendingChange.Container.ENDER_CHEST;
	}

	/** Target slot of a menu slot, or -1 for info and crafting slots. */
	private int targetSlot(int menuSlot) {
		if (view == View.INVENTORY) {
			return menuSlot < LiveInventoryMenu.CRAFT_START ? LiveInventoryMenu.INVENTORY_SLOT[menuSlot] : -1;
		}
		return menuSlot < 27 ? menuSlot : -1;
	}

	private ItemStack saved(int targetSlot) {
		return view == View.INVENTORY ? snapshot.inventory(targetSlot) : snapshot.enderChest(targetSlot);
	}

	@Override
	protected boolean isLocked(int slot) {
		return true;
	}

	@Override
	protected boolean stillAllowed() {
		return server().getPlayerList().getPlayer(targetId()) == null
				&& MejtaUtils.config().get(UtilsSettings.OFFLINE_VIEW)
				&& MejtaCoreAPI.hasPermission(viewer, view == View.INVENTORY ? UtilsPermissions.INVSEE_OFFLINE : UtilsPermissions.ECSEE_VIEW)
				&& (!mayQueue || canQueue(viewer));
	}

	/** The stored change if it is open and this viewer may edit it; a new one if there is none. */
	private Optional<PendingChange> editableChange() {
		Optional<PendingChange> existing = MejtaUtils.pending().get(targetId());
		if (existing.isEmpty()) {
			return Optional.of(new PendingChange(targetId(), snapshot.profile().name(), viewer.getUUID(),
					viewer.getGameProfile().name(), Instant.now()));
		}
		PendingChange change = existing.get();
		if (change.state() != PendingChange.State.OPEN) {
			notice = Component.literal("A finished change still waits for its items to be claimed; new changes are possible after that.")
					.withStyle(ChatFormatting.RED);
			return Optional.empty();
		}
		if (!change.staff().equals(viewer.getUUID())) {
			notice = Component.literal("Only " + change.staffName() + ", who queued the current change, can edit it.").withStyle(ChatFormatting.RED);
			return Optional.empty();
		}
		return Optional.of(change);
	}

	@Override
	protected void refresh() {
		Optional<PendingChange> change = MejtaUtils.pending().get(targetId());
		HolderLookup.Provider registries = server().registryAccess();
		for (int i = 0; i < infoStart; i++) {
			int slot = targetSlot(i);
			if (slot < 0) {
				display.setItem(i, MenuIcons.of(Items.BARRIER, Component.literal("Crafting grid").withStyle(ChatFormatting.GRAY),
						MenuIcons.line("Not saved for offline players: the game"), MenuIcons.line("returns these items on logout.")));
				continue;
			}
			Optional<PendingChange.SlotOp> op = change.flatMap(c -> c.op(container(), slot));
			ItemStack shown = saved(slot);
			if (op.isPresent() && op.get().kind() == PendingChange.Kind.ADD) {
				shown = ItemJson.decode(registries, op.get().item()).orElse(new ItemStack(Items.BARRIER));
				shown = MenuIcons.of(shown, shown.getHoverName().copy().withStyle(ChatFormatting.GREEN), List.of(
						Component.literal("Will be added when " + snapshot.profile().name() + " joins").withStyle(ChatFormatting.GREEN),
						MenuIcons.line("Click to undo (you get it back)")));
			} else if (op.isPresent() && !shown.isEmpty()) {
				shown = MenuIcons.of(shown, shown.getHoverName().copy().withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH), List.of(
						Component.literal("Will be removed when " + snapshot.profile().name() + " joins").withStyle(ChatFormatting.RED),
						MenuIcons.line("Click to undo")));
			}
			display.setItem(i, shown);
		}
		renderInfo(change);
	}

	private void renderInfo(Optional<PendingChange> change) {
		ServerPlayer p = snapshot.player();
		boolean inventory = view == View.INVENTORY;
		int base = infoStart;
		display.setItem(base, PlayerInfo.head(p, false, snapshot.dimension()));
		if (inventory) {
			display.setItem(base + 1, PlayerInfo.health(p));
			display.setItem(base + 2, PlayerInfo.food(p));
			display.setItem(base + 3, PlayerInfo.experience(p));
		}
		List<Component> pending = new ArrayList<>();
		if (change.isEmpty()) {
			pending.add(MenuIcons.line("No queued change"));
		} else {
			PendingChange c = change.get();
			pending.add(MenuIcons.line("State: " + c.state().name().toLowerCase(java.util.Locale.ROOT) + ", by " + c.staffName()));
			pending.add(MenuIcons.line(c.ops().size() + " slot(s), applied when the player joins"));
			if (!c.claims().isEmpty()) {
				pending.add(MenuIcons.line(c.claims().size() + " item(s) waiting in /invsee claims"));
			}
		}
		if (!notice.getString().isEmpty()) {
			pending.add(notice);
		}
		display.setItem(base + 4, MenuIcons.of(Items.CLOCK, Component.literal("Queued change").withStyle(ChatFormatting.GOLD), pending));
		display.setItem(base + 5, mayQueue
				? MenuIcons.of(Items.WRITABLE_BOOK, Component.literal("Queue changes (experimental)").withStyle(ChatFormatting.GOLD),
						MenuIcons.line("Click an item: queue removal (click again: undo)"),
						MenuIcons.line("Shift-click your own item: queue adding it"),
						MenuIcons.line("Nothing touches the save file; changes apply on join"))
				: MenuIcons.of(Items.BOOK, Component.literal("View only").withStyle(ChatFormatting.GREEN),
						MenuIcons.line("Copy of the saved data; nothing can be taken")));
		if (mayQueue) {
			boolean delete = change.map(PendingChange::deleteRemoved).orElse(false);
			display.setItem(base + 6, MenuIcons.of(delete ? Items.LAVA_BUCKET : Items.CHEST,
					Component.literal("Removed items: " + (delete ? "deleted" : "go to your claims")).withStyle(ChatFormatting.YELLOW),
					MenuIcons.line("Click to switch")));
		}
		display.setItem(base + 7, inventory
				? MenuIcons.of(Items.ENDER_CHEST, Component.literal("Saved ender chest").withStyle(ChatFormatting.DARK_PURPLE))
				: MenuIcons.of(Items.CHEST, Component.literal("Saved inventory").withStyle(ChatFormatting.YELLOW)));
		display.setItem(base + 8, MenuIcons.of(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED)));
	}

	@Override
	protected void onLockedClick(int slot, ContainerInput input, int button) {
		if (input != ContainerInput.PICKUP) {
			return;
		}
		if (slot >= infoStart) {
			switch (slot - infoStart) {
				case 6 -> toggleDeleteRemoved();
				case 7 -> {
					close();
					open(viewer, snapshot, view == View.INVENTORY ? View.ENDER_CHEST : View.INVENTORY, mayQueue);
				}
				case 8 -> close();
				default -> {
				}
			}
			return;
		}
		int target = targetSlot(slot);
		if (!mayQueue || target < 0) {
			return;
		}
		Optional<PendingChange> found = editableChange();
		if (found.isEmpty()) {
			refresh();
			return;
		}
		PendingChange change = found.get();
		HolderLookup.Provider registries = server().registryAccess();
		Optional<PendingChange.SlotOp> op = change.op(container(), target);
		try {
			if (op.isPresent()) {
				change.undo(container(), target);
				MejtaUtils.pending().save(change); // written before an added item is given back
				if (op.get().kind() == PendingChange.Kind.ADD) {
					giveBack(registries, op.get().item());
				}
				audit("invsee.offline.undo", target, op.get().item(), registries);
			} else {
				ItemStack item = saved(target);
				if (item.isEmpty()) {
					return;
				}
				JsonElement json = ItemJson.encode(registries, item);
				change.remove(container(), target, json);
				MejtaUtils.pending().save(change);
				audit("invsee.offline.remove", target, json, registries);
			}
		} catch (IOException e) {
			MejtaUtils.log().error("Could not save queued change for {}", snapshot.profile().name(), e);
			viewer.sendSystemMessage(Component.literal("Could not save the change; nothing was queued.").withStyle(ChatFormatting.RED));
		}
		refresh();
	}

	@Override
	protected void onViewerInventoryClick(int slotId, ContainerInput input, int button) {
		if (!mayQueue || input != ContainerInput.QUICK_MOVE || slotId >= slots.size()) {
			return;
		}
		Slot from = slots.get(slotId);
		ItemStack stack = from.getItem();
		if (stack.isEmpty()) {
			return;
		}
		Optional<PendingChange> found = editableChange();
		if (found.isEmpty()) {
			refresh();
			return;
		}
		PendingChange change = found.get();
		int free = firstFreeSlot(change);
		if (free < 0) {
			viewer.sendSystemMessage(Component.literal("No free slot in the saved " + (view == View.INVENTORY ? "inventory" : "ender chest")
					+ ".").withStyle(ChatFormatting.RED));
			return;
		}
		HolderLookup.Provider registries = server().registryAccess();
		ItemStack taken = stack.copy();
		JsonElement json = ItemJson.encode(registries, taken);
		audit("invsee.offline.add", free, json, registries);
		// Taken and saved first, then queued: a crash in between can lose this stack, never duplicate it.
		from.set(ItemStack.EMPTY);
		server().getPlayerList().saveAll();
		change.add(container(), free, json);
		try {
			MejtaUtils.pending().save(change);
		} catch (IOException e) {
			MejtaUtils.log().error("Could not save queued change for {}", snapshot.profile().name(), e);
			change.undo(container(), free);
			giveBackStack(taken);
			viewer.sendSystemMessage(Component.literal("Could not save the change; the item was returned.").withStyle(ChatFormatting.RED));
		}
		refresh();
	}

	private int firstFreeSlot(PendingChange change) {
		int end = view == View.INVENTORY ? 36 : 27;
		for (int i = 0; i < end; i++) {
			if (saved(i).isEmpty() && change.op(container(), i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	private void toggleDeleteRemoved() {
		if (!mayQueue) {
			return;
		}
		editableChange().ifPresent(change -> {
			change.setDeleteRemoved(!change.deleteRemoved());
			try {
				MejtaUtils.pending().save(change);
			} catch (IOException e) {
				MejtaUtils.log().error("Could not save queued change", e);
			}
		});
		refresh();
	}

	private void giveBack(HolderLookup.Provider registries, JsonElement json) {
		ItemJson.decode(registries, json).ifPresentOrElse(this::giveBackStack, () ->
				viewer.sendSystemMessage(Component.literal("The item no longer exists in this game version.").withStyle(ChatFormatting.RED)));
	}

	private void giveBackStack(ItemStack stack) {
		ItemStack copy = stack.copy();
		if (!viewer.getInventory().add(copy) && !copy.isEmpty()) {
			viewer.drop(copy, false);
		}
		server().getPlayerList().saveAll();
	}

	private void audit(String action, int slot, JsonElement item, HolderLookup.Provider registries) {
		String described = ItemJson.decode(registries, item).map(ItemJson::describe).orElse("unknown item");
		UtilsAudit.record(action, MejtaCoreAPI.actor(viewer), targetId(), snapshot.profile().name(), AuditOutcome.SUCCESS,
				Map.of("container", container().name(), "slot", slot, "item", described), null);
	}

	@Override
	public ItemStack quickMoveStack(Player who, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	protected void onClosed() {
		OPEN.remove(this);
	}
}
