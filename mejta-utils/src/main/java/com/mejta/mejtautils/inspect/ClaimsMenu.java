package com.mejta.mejtautils.inspect;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.offline.PendingChange;
import com.mejta.mejtautils.util.ItemJson;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * {@code /invsee claims}: items waiting for the staff member: removed by an applied offline change,
 * or returned because their change was cancelled. Click one to take it.
 */
public final class ClaimsMenu {
	private static final int ROWS = 6;

	private ClaimsMenu() {
	}

	private record Entry(PendingChange change, int index) {
	}

	/** Permission-checked here; refused in staff mode (its inventory is replaced on exit, so claimed items would be lost). */
	public static void open(ServerPlayer staff) {
		if (!MejtaCoreAPI.hasPermission(staff, UtilsPermissions.INVSEE_OFFLINE)) {
			staff.sendSystemMessage(Component.literal("You need " + UtilsPermissions.INVSEE_OFFLINE.node() + " for that.").withStyle(ChatFormatting.RED));
			UtilsAudit.record("invsee.claims.open", MejtaCoreAPI.actor(staff), null, null, AuditOutcome.DENIED,
					Map.of("missing_permission", UtilsPermissions.INVSEE_OFFLINE.node()), null);
			return;
		}
		if (MejtaUtils.staffMode().isActive(staff.getUUID())) {
			staff.sendSystemMessage(Component.literal("Leave staff mode before taking your claims: items taken now would be lost when it ends.")
					.withStyle(ChatFormatting.RED));
			UtilsAudit.record("invsee.claims.open", MejtaCoreAPI.actor(staff), null, null, AuditOutcome.DENIED,
					Map.of("reason", "in staff mode"), null);
			return;
		}
		List<Entry> entries = new java.util.ArrayList<>();
		for (PendingChange change : MejtaUtils.pending().claimsOf(staff.getUUID())) {
			for (int i = 0; i < change.claims().size() && entries.size() < ROWS * 9 - 1; i++) {
				entries.add(new Entry(change, i));
			}
		}
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Your claims (" + entries.size() + ")"), ROWS)
				.validWhile(p -> MejtaCoreAPI.hasPermission(p, UtilsPermissions.INVSEE_OFFLINE) && !MejtaUtils.staffMode().isActive(p.getUUID()));
		for (int slot = 0; slot < entries.size(); slot++) {
			Entry entry = entries.get(slot);
			ItemStack stack = ItemJson.decode(staff.level().getServer().registryAccess(), entry.change().claims().get(entry.index()))
					.orElse(new ItemStack(Items.BARRIER));
			String why = entry.change().state() == PendingChange.State.CANCELLED ? "returned: " + entry.change().note()
					: "removed from " + entry.change().targetName();
			menu.button(slot, () -> MenuIcons.of(stack, stack.getHoverName(), List.of(MenuIcons.line(why), MenuIcons.line("Click to take it"))),
					click -> {
						click.menu().close();
						if (take(staff, entry)) {
							open(staff); // indexes changed: rebuild
						}
					});
		}
		if (entries.isEmpty()) {
			menu.icon(22, () -> MenuIcons.of(Items.PAPER, Component.literal("Nothing to claim").withStyle(ChatFormatting.GRAY)));
		}
		menu.open(staff);
	}

	/** @return whether the menu may be shown again */
	private static boolean take(ServerPlayer staff, Entry entry) {
		if (!MejtaCoreAPI.hasPermission(staff, UtilsPermissions.INVSEE_OFFLINE) || MejtaUtils.staffMode().isActive(staff.getUUID())) {
			staff.sendSystemMessage(Component.literal("You may not take claims now.").withStyle(ChatFormatting.RED));
			return false;
		}
		// Re-read: another menu (or an earlier click) may have taken claims since this one was built.
		PendingChange fresh = MejtaUtils.pending().get(entry.change().target()).orElse(null);
		if (fresh == null || !fresh.staff().equals(staff.getUUID()) || entry.index() >= fresh.claims().size()
				|| !fresh.claims().get(entry.index()).equals(entry.change().claims().get(entry.index()))) {
			staff.sendSystemMessage(Component.literal("That claim changed meanwhile; try again.").withStyle(ChatFormatting.YELLOW));
			return true;
		}
		try {
			if (!MejtaUtils.pending().claim(staff, fresh, entry.index()) && staff.getInventory().getFreeSlot() < 0) {
				staff.sendSystemMessage(Component.literal("Make room in your inventory first.").withStyle(ChatFormatting.RED));
			}
		} catch (IOException e) {
			MejtaUtils.log().error("Could not save claim of {}", staff.getGameProfile().name(), e);
			staff.sendSystemMessage(Component.literal("Could not save; nothing was given.").withStyle(ChatFormatting.RED));
		}
		return true;
	}
}
