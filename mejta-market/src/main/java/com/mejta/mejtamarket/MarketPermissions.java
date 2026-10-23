package com.mejta.mejtamarket;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaMarket's permission nodes. Server owners can change their levels in {@code mejtacore.permissions.json}. */
public final class MarketPermissions {
	public static final MejtaPermission USE = MejtaPermission.of("mejtamarket.use", PermissionLevel.ALL)
			.describedAs("Open the auction house (/ah), buy listings and collect from your collection box");
	public static final MejtaPermission SELL = MejtaPermission.of("mejtamarket.sell", PermissionLevel.ALL)
			.describedAs("Put items on sale with /ah sell");
	public static final MejtaPermission ORDERS = MejtaPermission.of("mejtamarket.orders", PermissionLevel.ALL)
			.describedAs("Place buy orders and sell items into them");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtamarket.admin", PermissionLevel.ADMINS)
			.describedAs("Remove anyone's listings and buy orders, edit the price list and control the market bot (audited)");

	public static final MejtaPermission[] ALL = {USE, SELL, ORDERS, ADMIN};

	private MarketPermissions() {
	}
}
