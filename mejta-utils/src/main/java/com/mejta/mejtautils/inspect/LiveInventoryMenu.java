package com.mejta.mejtautils.inspect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * {@code /invsee} of an online player: 54 slots bound to the target's own inventory.
 *
 * <pre>
 * rows 1-3   main inventory (slots 9-35)
 * row 4      hotbar (slots 0-8)
 * row 5      helmet, chestplate, leggings, boots, offhand | 2x2 crafting grid (always view-only)
 * row 6      info: player, health, food, XP, effects, mode, status panel, ender chest, close
 * </pre>
 */
public final class LiveInventoryMenu extends InspectMenu {
	/** Menu slot -> target inventory slot, for rows 1-5 (the first 41 menu slots). */
	static final int[] INVENTORY_SLOT = new int[41];
	static final int CRAFT_START = 41;
	static final int INFO_START = 45;

	static {
		for (int i = 0; i < 27; i++) {
			INVENTORY_SLOT[i] = 9 + i;
		}
		for (int i = 0; i < 9; i++) {
			INVENTORY_SLOT[27 + i] = i;
		}
		INVENTORY_SLOT[36] = 39; // head
		INVENTORY_SLOT[37] = 38; // chest
		INVENTORY_SLOT[38] = 37; // legs
		INVENTORY_SLOT[39] = 36; // feet
		INVENTORY_SLOT[40] = Inventory.SLOT_OFFHAND;
	}

	private final ServerPlayer target;
	private final SimpleContainer display = new SimpleContainer(13); // menu slots 41-53
	private final ItemStack[] before = new ItemStack[41];

	private LiveInventoryMenu(int containerId, Inventory viewerInventory, ServerPlayer viewer, ServerPlayer target, boolean edit) {
		super(chestType(6), containerId, viewer, 6, edit);
		this.target = target;
		Inventory inv = target.getInventory();
		for (int i = 0; i < 41; i++) {
			addSlot(new TargetSlot(inv, INVENTORY_SLOT[i], editable));
			before[i] = inv.getItem(INVENTORY_SLOT[i]).copy();
		}
		for (int i = 0; i < 13; i++) {
			addSlot(new LockedSlot(display, i));
		}
		addStandardInventorySlots(viewerInventory, 0, 0);
		refresh();
	}

	/** Opens the menu; the caller has checked permissions. */
	public static void open(ServerPlayer viewer, ServerPlayer target, boolean edit) {
		Component title = Component.literal((edit ? "Editing " : "Viewing ") + target.getGameProfile().name());
		viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new LiveInventoryMenu(id, inv, viewer, target, edit), title));
	}

	@Override
	protected boolean isLocked(int slot) {
		return slot >= CRAFT_START;
	}

	@Override
	protected boolean stillAllowed() {
		if (target.isRemoved() || target.hasDisconnected()) {
			return false;
		}
		boolean may = MejtaCoreAPI.hasPermission(viewer, editable ? UtilsPermissions.INVSEE_EDIT : UtilsPermissions.INVSEE_VIEW);
		return may && com.mejta.mejtautils.util.Targets.mayActOn(viewer, target);
	}

	@Override
	protected void refresh() {
		// The 2x2 crafting grid lives in the target's inventory menu (slots 1-4); shown as copies.
		for (int i = 0; i < 4; i++) {
			display.setItem(i, target.inventoryMenu.getSlot(1 + i).getItem().copy());
		}
		display.setItem(4, PlayerInfo.head(target, true));
		display.setItem(5, PlayerInfo.health(target));
		display.setItem(6, PlayerInfo.food(target));
		display.setItem(7, PlayerInfo.experience(target));
		display.setItem(8, PlayerInfo.effects(target, List.of()));
		display.setItem(9, editable
				? MenuIcons.of(Items.WRITABLE_BOOK, Component.literal("Edit mode").withStyle(ChatFormatting.GOLD),
						MenuIcons.line("Changes go straight to the player's inventory"),
						MenuIcons.line("The crafting grid is always view-only"))
				: MenuIcons.of(Items.BOOK, Component.literal("View mode").withStyle(ChatFormatting.GREEN),
						MenuIcons.line("Nothing can be taken or changed")));
		display.setItem(10, MenuIcons.of(Items.CLOCK, Component.literal("Status panel").withStyle(ChatFormatting.AQUA),
				MenuIcons.line("Click: health, effects, game mode")));
		display.setItem(11, MenuIcons.of(Items.ENDER_CHEST, Component.literal("Ender chest").withStyle(ChatFormatting.DARK_PURPLE),
				MenuIcons.line("Click: view this player's ender chest")));
		display.setItem(12, MenuIcons.of(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED)));
	}

	@Override
	protected void onLockedClick(int slot, ContainerInput input, int button) {
		if (input != ContainerInput.PICKUP) {
			return;
		}
		switch (slot - CRAFT_START) {
			case 10 -> {
				close();
				StatusMenu.openFor(viewer, target);
			}
			case 11 -> {
				close();
				Inspect.openEnderChest(viewer, target, false);
			}
			case 12 -> close();
			default -> {
			}
		}
	}

	@Override
	public ItemStack quickMoveStack(Player who, int index) {
		return quickMove(index, 41);
	}

	@Override
	protected void onClosed() {
		if (!editable) {
			return;
		}
		// One audit entry per edit session: every slot that differs from when the menu opened. The
		// player may have changed some of them themselves meanwhile; the entry says so.
		Map<String, String> changes = new LinkedHashMap<>();
		for (int i = 0; i < 41 && changes.size() < 30; i++) {
			ItemStack now = target.getInventory().getItem(INVENTORY_SLOT[i]);
			if (!ItemStack.matches(now, before[i])) {
				changes.put("slot " + INVENTORY_SLOT[i], ItemJson.describe(before[i]) + " -> " + ItemJson.describe(now));
			}
		}
		if (!changes.isEmpty()) {
			changes.put("note", "differences while the menu was open; may include the player's own changes");
			UtilsAudit.record("invsee.edit", viewer, target, AuditOutcome.SUCCESS, changes);
		}
	}
}
