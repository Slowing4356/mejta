package com.mejta.mejtautils;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * MejtaUtils' permission nodes. Server owners can change the required operator level of each one in
 * {@code config/mejta/mejtacore.permissions.json}. Every action re-checks its node when it happens,
 * not only when the command is typed.
 */
public final class UtilsPermissions {
	public static final MejtaPermission INVSEE_VIEW = MejtaPermission.of("mejtautils.invsee.view", PermissionLevel.MODERATORS)
			.describedAs("View online players' inventories with /invsee");
	public static final MejtaPermission INVSEE_EDIT = MejtaPermission.of("mejtautils.invsee.edit", PermissionLevel.GAMEMASTERS)
			.describedAs("Edit online players' inventories with /invsee <player> edit");
	public static final MejtaPermission INVSEE_OFFLINE = MejtaPermission.of("mejtautils.invsee.offline", PermissionLevel.MODERATORS)
			.describedAs("View offline players' saved inventories and ender chests");
	public static final MejtaPermission INVSEE_OFFLINE_EDIT = MejtaPermission.of("mejtautils.invsee.offline_edit", PermissionLevel.ADMINS)
			.describedAs("Queue changes to offline players' inventories (experimental; also needs invsee.offline.edit on)");
	public static final MejtaPermission ECSEE_VIEW = MejtaPermission.of("mejtautils.ecsee.view", PermissionLevel.MODERATORS)
			.describedAs("View players' ender chests with /ecsee");
	public static final MejtaPermission ECSEE_EDIT = MejtaPermission.of("mejtautils.ecsee.edit", PermissionLevel.GAMEMASTERS)
			.describedAs("Edit online players' ender chests with /ecsee <player> edit");
	public static final MejtaPermission STATUS_VIEW = MejtaPermission.of("mejtautils.status.view", PermissionLevel.MODERATORS)
			.describedAs("Open the player status panel with /status");
	public static final MejtaPermission STATUS_EFFECTS = MejtaPermission.of("mejtautils.status.effects", PermissionLevel.GAMEMASTERS)
			.describedAs("Clear a player's effects from the status panel");
	public static final MejtaPermission STATUS_GAMEMODE = MejtaPermission.of("mejtautils.status.gamemode", PermissionLevel.GAMEMASTERS)
			.describedAs("Change a player's game mode from the status panel");
	public static final MejtaPermission VANISH = MejtaPermission.of("mejtautils.vanish", PermissionLevel.MODERATORS)
			.describedAs("Use /vanish and /vanish settings");
	public static final MejtaPermission VANISH_SEE = MejtaPermission.of("mejtautils.vanish.see", PermissionLevel.MODERATORS)
			.describedAs("See vanished staff");
	public static final MejtaPermission VANISH_FAKE = MejtaPermission.of("mejtautils.vanish.fake", PermissionLevel.ADMINS)
			.describedAs("Use /vanish fakequit and /vanish fakejoin (only if vanish.fake_presence.enabled is on)");
	public static final MejtaPermission FREEZE = MejtaPermission.of("mejtautils.freeze", PermissionLevel.MODERATORS)
			.describedAs("Freeze and unfreeze players");
	public static final MejtaPermission FREEZE_INFO = MejtaPermission.of("mejtautils.freeze.info", PermissionLevel.MODERATORS)
			.describedAs("See freeze diagnostics and freeze alerts");
	public static final MejtaPermission FREEZE_EXEMPT = MejtaPermission.of("mejtautils.freeze.exempt", PermissionLevel.OWNERS)
			.describedAs("Cannot be frozen");
	public static final MejtaPermission STAFFMODE = MejtaPermission.of("mejtautils.staffmode", PermissionLevel.MODERATORS)
			.describedAs("Use /staffmode and its tools");
	public static final MejtaPermission STAFFMODE_TELEPORT = MejtaPermission.of("mejtautils.staffmode.teleport", PermissionLevel.MODERATORS)
			.describedAs("Use the staff-mode random-player compass");
	public static final MejtaPermission STAFFMODE_RESTORE = MejtaPermission.of("mejtautils.staffmode.restore", PermissionLevel.ADMINS)
			.describedAs("Confirm an unsafe staff-mode restoration for another staff member");
	public static final MejtaPermission PROTECTED = MejtaPermission.of("mejtautils.protected", PermissionLevel.OWNERS)
			.describedAs("Can only be inspected, edited or frozen by actors who also have this node");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtautils.admin", PermissionLevel.ADMINS)
			.describedAs("Use /mejtaadmin mejtautils (pending offline changes, staff-mode sessions, claims)");

	public static final MejtaPermission[] ALL = {INVSEE_VIEW, INVSEE_EDIT, INVSEE_OFFLINE, INVSEE_OFFLINE_EDIT, ECSEE_VIEW, ECSEE_EDIT,
			STATUS_VIEW, STATUS_EFFECTS, STATUS_GAMEMODE, VANISH, VANISH_SEE, VANISH_FAKE, FREEZE, FREEZE_INFO, FREEZE_EXEMPT,
			STAFFMODE, STAFFMODE_TELEPORT, STAFFMODE_RESTORE, PROTECTED, ADMIN};

	private UtilsPermissions() {
	}
}
