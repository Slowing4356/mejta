package com.mejta.mejtautils.inspect;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * {@code /ecsee} of an online player: the 27 slots of their own ender chest container. If the player
 * has their ender chest open at the same time, both menus act on that one container on the server
 * thread, as vanilla does for two players at one chest, so nothing can be duplicated.
 */
public final class LiveEnderChestMenu extends InspectMenu {
	private final ServerPlayer target;
	private final ItemStack[] before = new ItemStack[27];

	private LiveEnderChestMenu(int containerId, Inventory viewerInventory, ServerPlayer viewer, ServerPlayer target, boolean edit) {
		super(chestType(3), containerId, viewer, 3, edit);
		this.target = target;
		PlayerEnderChestContainer chest = target.getEnderChestInventory();
		for (int i = 0; i < 27; i++) {
			addSlot(new TargetSlot(chest, i, editable));
			before[i] = chest.getItem(i).copy();
		}
		addStandardInventorySlots(viewerInventory, 0, 0);
	}

	public static void open(ServerPlayer viewer, ServerPlayer target, boolean edit) {
		Component title = Component.literal((edit ? "Editing " : "Viewing ") + target.getGameProfile().name() + "'s ender chest");
		viewer.openMenu(new SimpleMenuProvider((id, inv, p) -> new LiveEnderChestMenu(id, inv, viewer, target, edit), title));
	}

	@Override
	protected boolean isLocked(int slot) {
		return false;
	}

	@Override
	protected boolean stillAllowed() {
		if (target.isRemoved() || target.hasDisconnected()) {
			return false;
		}
		return MejtaCoreAPI.hasPermission(viewer, editable ? UtilsPermissions.ECSEE_EDIT : UtilsPermissions.ECSEE_VIEW)
				&& Targets.mayActOn(viewer, target);
	}

	@Override
	protected void refresh() {
		// The slots read the live container; vanilla sends changes every tick.
	}

	@Override
	public ItemStack quickMoveStack(Player who, int index) {
		return quickMove(index, 27);
	}

	@Override
	protected void onClosed() {
		if (!editable) {
			return;
		}
		Map<String, String> changes = new LinkedHashMap<>();
		for (int i = 0; i < 27 && changes.size() < 30; i++) {
			ItemStack now = target.getEnderChestInventory().getItem(i);
			if (!ItemStack.matches(now, before[i])) {
				changes.put("slot " + i, ItemJson.describe(before[i]) + " -> " + ItemJson.describe(now));
			}
		}
		if (!changes.isEmpty()) {
			changes.put("note", "differences while the menu was open; may include the player's own changes");
			UtilsAudit.record("ecsee.edit", viewer, target, AuditOutcome.SUCCESS, changes);
		}
	}
}
