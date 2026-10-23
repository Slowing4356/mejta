package com.mejta.mejtacrates;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaCrates' permission nodes. Server owners can change their levels in {@code mejtacore.permissions.json}. */
public final class CratePermissions {
	public static final MejtaPermission USE = MejtaPermission.of("mejtacrates.use", PermissionLevel.ALL)
			.describedAs("Open crates with your keys, preview their rewards and see your keys (/crates)");
	public static final MejtaPermission GIVE = MejtaPermission.of("mejtacrates.give", PermissionLevel.ADMINS)
			.describedAs("Give and take players' keys, also offline players' (/crates give|giveall|take; audited). The console always may");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtacrates.admin", PermissionLevel.ADMINS)
			.describedAs("Create and edit crates and their rewards, place crate blocks and see anyone's keys (/crates admin)");
	public static final MejtaPermission ALERTS = MejtaPermission.of("mejtacrates.alerts", PermissionLevel.MODERATORS)
			.describedAs("See in chat when keys are given or taken and when a copied physical key is caught");

	public static final MejtaPermission[] ALL = {USE, GIVE, ADMIN, ALERTS};

	private CratePermissions() {
	}
}
