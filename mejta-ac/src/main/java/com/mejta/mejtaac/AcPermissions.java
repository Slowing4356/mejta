package com.mejta.mejtaac;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * MejtaAC's permission nodes. Server owners can change the required operator level of each one in
 * {@code config/mejta/mejtacore.permissions.json}.
 */
public final class AcPermissions {
	public static final MejtaPermission ALERTS = MejtaPermission.of("mejtaac.alerts", PermissionLevel.MODERATORS)
			.describedAs("See anti-cheat alerts in chat and use status, vl and history commands");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtaac.admin", PermissionLevel.GAMEMASTERS)
			.describedAs("Reset violation levels");
	public static final MejtaPermission BYPASS = MejtaPermission.of("mejtaac.bypass", PermissionLevel.OWNERS)
			.describedAs("Exempt from all checks (only if exemptions.bypass_permission is on)");

	private AcPermissions() {
	}
}
