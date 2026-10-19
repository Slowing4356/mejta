package com.mejta.mejtacorpse;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaCorpse's permission nodes. Server owners can change their levels in {@code mejtacore.permissions.json}. */
public final class CorpsePermissions {
	public static final MejtaPermission USE = MejtaPermission.of("mejtacorpse.use", PermissionLevel.ALL)
			.describedAs("Open your own corpses and list them with /corpse");
	public static final MejtaPermission OPEN_ANY = MejtaPermission.of("mejtacorpse.open_any", PermissionLevel.ADMINS)
			.describedAs("Open anyone's corpse (audited)");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtacorpse.admin", PermissionLevel.ADMINS)
			.describedAs("List any player's corpses, open them from anywhere and teleport to them (/corpse list|open|tp)");

	public static final MejtaPermission[] ALL = {USE, OPEN_ANY, ADMIN};

	private CorpsePermissions() {
	}
}
