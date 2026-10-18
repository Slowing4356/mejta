package com.mejta.mejtautils.staffmode;

import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.inspect.InspectMenu;

/** Staff-mode rules for the packet mixins. Safe when MejtaUtils is not initialized. */
public final class StaffModeHooks {
	private StaffModeHooks() {
	}

	/**
	 * In staff mode every real container (chests, furnaces, other players' menus...) is view-only: items
	 * taken would be destroyed when staff mode ends, and tools must not be stored. The player's own
	 * inventory, MejtaCore server menus and MejtaUtils' inspection menus (which enforce their own rules)
	 * still work.
	 */
	public static boolean containerClickBlocked(ServerPlayer player) {
		StaffModeManager m = MejtaUtils.staffModeOrNull();
		if (m == null || !m.isActive(player.getUUID())) {
			return false;
		}
		var menu = player.containerMenu;
		return menu != player.inventoryMenu && !(menu instanceof InspectMenu) && !ServerMenu.isServerMenu(menu);
	}
}
