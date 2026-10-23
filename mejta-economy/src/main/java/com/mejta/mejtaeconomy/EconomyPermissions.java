package com.mejta.mejtaeconomy;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaEconomy's permission nodes. Server owners can change their levels in {@code mejtacore.permissions.json}. */
public final class EconomyPermissions {
	public static final MejtaPermission BALANCE = MejtaPermission.of("mejtaeconomy.balance", PermissionLevel.ALL)
			.describedAs("See your balance with /balance (/bal)");
	public static final MejtaPermission BALANCE_OTHERS = MejtaPermission.of("mejtaeconomy.balance.others", PermissionLevel.ALL)
			.describedAs("See other players' balances with /balance <player>");
	public static final MejtaPermission PAY = MejtaPermission.of("mejtaeconomy.pay", PermissionLevel.ALL)
			.describedAs("Send money to other players with /pay");
	public static final MejtaPermission BALTOP = MejtaPermission.of("mejtaeconomy.baltop", PermissionLevel.ALL)
			.describedAs("See the richest players with /baltop");
	public static final MejtaPermission HISTORY = MejtaPermission.of("mejtaeconomy.history", PermissionLevel.ALL)
			.describedAs("See your own recent transactions with /eco history");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtaeconomy.admin", PermissionLevel.ADMINS)
			.describedAs("Give, take and set any player's money and see anyone's transactions (/eco give|take|set|reset|history; audited)");

	public static final MejtaPermission[] ALL = {BALANCE, BALANCE_OTHERS, PAY, BALTOP, HISTORY, ADMIN};

	private EconomyPermissions() {
	}
}
