package com.mejta.mejtasmp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaSMP's permission nodes. Levels can be changed in {@code config/mejta/mejtacore.permissions.json}. */
public final class SmpPermissions {
	public static final MejtaPermission SIDEBAR = MejtaPermission.of("mejtasmp.sidebar", PermissionLevel.ALL)
			.describedAs("Pick what your sidebar shows with /sb");
	public static final MejtaPermission LEADERBOARD = MejtaPermission.of("mejtasmp.leaderboard", PermissionLevel.ALL)
			.describedAs("Open /leaderboard");
	public static final MejtaPermission DIMENSION_BYPASS = MejtaPermission.of("mejtasmp.dimensions.bypass", PermissionLevel.ADMINS)
			.describedAs("Use portals to a closed dimension (only while op_bypass.enabled is on)");
	public static final MejtaPermission ANTIALT_BYPASS = MejtaPermission.of("mejtasmp.antialt.bypass", PermissionLevel.MODERATORS)
			.describedAs("Never refused by the account limit, and not counted in it (only while op_bypass.enabled is on)");
	public static final MejtaPermission ANTIALT_ALERTS = MejtaPermission.of("mejtasmp.antialt.alerts", PermissionLevel.MODERATORS)
			.describedAs("Told when the account limit refuses a player (names only, never addresses)");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtasmp.admin", PermissionLevel.ADMINS)
			.describedAs("Use /mejtaadmin mejtasmp status");

	public static final MejtaPermission[] ALL = {SIDEBAR, LEADERBOARD, DIMENSION_BYPASS, ANTIALT_BYPASS, ANTIALT_ALERTS, ADMIN};

	/** Whether {@code player} has {@code bypass}: bypass permissions only count while {@code op_bypass.enabled} is on. */
	public static boolean bypasses(ServerPlayer player, MejtaPermission bypass) {
		ConfigHandle config = MejtaSMP.configOrNull();
		return config != null && config.get(SmpSettings.OP_BYPASS) && MejtaCoreAPI.hasPermission(player, bypass);
	}

	private SmpPermissions() {
	}
}
