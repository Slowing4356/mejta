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

	public static final MejtaPermission RESTORE = MejtaPermission.of("mejtacorpse.restore", PermissionLevel.ADMINS)
			.describedAs("List players' death backups and give a backup's items back to its owner once its corpse is gone (audited; each backup once)");
	public static final MejtaPermission RESTORE_SELF = MejtaPermission.of("mejtacorpse.restore.self", PermissionLevel.OWNERS)
			.describedAs("Restore your own death backups too (with mejtacorpse.restore)");

	public static final MejtaPermission[] ALL = {USE, OPEN_ANY, ADMIN, RESTORE, RESTORE_SELF};

	private CorpsePermissions() {
	}
}
