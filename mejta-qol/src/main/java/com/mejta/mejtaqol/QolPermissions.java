package com.mejta.mejtaqol;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * MejtaQoL's permission nodes. Player features default to level 0 (everyone); server owners can raise
 * any of them in {@code config/mejta/mejtacore.permissions.json}. Every action re-checks its node when
 * it happens (a teleport also when its wait ends).
 */
public final class QolPermissions {
	public static final MejtaPermission TPA = MejtaPermission.of("mejtaqol.tpa", PermissionLevel.ALL)
			.describedAs("Use /tpa, /tpaccept, /tpdeny, /tpacancel and /tptoggle");
	public static final MejtaPermission TPAHERE = MejtaPermission.of("mejtaqol.tpahere", PermissionLevel.ALL)
			.describedAs("Use /tpahere");
	public static final MejtaPermission HOME = MejtaPermission.of("mejtaqol.home", PermissionLevel.ALL)
			.describedAs("Use /sethome, /home, /delhome and /homes");
	public static final MejtaPermission HOMES_EXTRA = MejtaPermission.of("mejtaqol.homes.extra", PermissionLevel.MODERATORS)
			.describedAs("Have homes.max_extra homes instead of homes.max");
	public static final MejtaPermission HOMES_OTHERS = MejtaPermission.of("mejtaqol.homes.others", PermissionLevel.ADMINS)
			.describedAs("List, visit and delete other players' homes (/homes <player>)");
	public static final MejtaPermission BACK = MejtaPermission.of("mejtaqol.back", PermissionLevel.ALL)
			.describedAs("Use /back");
	public static final MejtaPermission SPAWN = MejtaPermission.of("mejtaqol.spawn", PermissionLevel.ALL)
			.describedAs("Use /spawn");
	public static final MejtaPermission SETSPAWN = MejtaPermission.of("mejtaqol.setspawn", PermissionLevel.ADMINS)
			.describedAs("Set the /spawn point with /setspawn");
	public static final MejtaPermission WARP = MejtaPermission.of("mejtaqol.warp", PermissionLevel.ALL)
			.describedAs("Use /warp and /warps");
	public static final MejtaPermission WARPS_MANAGE = MejtaPermission.of("mejtaqol.warps.manage", PermissionLevel.ADMINS)
			.describedAs("Create and delete warps with /setwarp and /delwarp");
	public static final MejtaPermission RTP = MejtaPermission.of("mejtaqol.rtp", PermissionLevel.ALL)
			.describedAs("Use /rtp");
	public static final MejtaPermission ENDERCHEST = MejtaPermission.of("mejtaqol.enderchest", PermissionLevel.ALL)
			.describedAs("Open your own ender chest with /enderchest or /ec");
	public static final MejtaPermission WORKBENCH = MejtaPermission.of("mejtaqol.workstation.crafting", PermissionLevel.ALL)
			.describedAs("Use /workbench and /craft");
	public static final MejtaPermission ANVIL = MejtaPermission.of("mejtaqol.workstation.anvil", PermissionLevel.ALL)
			.describedAs("Use /anvil");
	public static final MejtaPermission STONECUTTER = MejtaPermission.of("mejtaqol.workstation.stonecutter", PermissionLevel.ALL)
			.describedAs("Use /stonecutter");
	public static final MejtaPermission GRINDSTONE = MejtaPermission.of("mejtaqol.workstation.grindstone", PermissionLevel.ALL)
			.describedAs("Use /grindstone");
	public static final MejtaPermission SMITHING = MejtaPermission.of("mejtaqol.workstation.smithing", PermissionLevel.ALL)
			.describedAs("Use /smithing");
	public static final MejtaPermission LOOM = MejtaPermission.of("mejtaqol.workstation.loom", PermissionLevel.ALL)
			.describedAs("Use /loom");
	public static final MejtaPermission CARTOGRAPHY = MejtaPermission.of("mejtaqol.workstation.cartography", PermissionLevel.ALL)
			.describedAs("Use /cartography");
	public static final MejtaPermission TRASH = MejtaPermission.of("mejtaqol.trash", PermissionLevel.ALL)
			.describedAs("Use /trash");
	public static final MejtaPermission AFK = MejtaPermission.of("mejtaqol.afk", PermissionLevel.ALL)
			.describedAs("Use /afk");
	public static final MejtaPermission AFK_KICK_EXEMPT = MejtaPermission.of("mejtaqol.afk.kick_exempt", PermissionLevel.MODERATORS)
			.describedAs("Never kicked for being idle (only while op_bypass.enabled is on)");
	public static final MejtaPermission REPLY = MejtaPermission.of("mejtaqol.reply", PermissionLevel.ALL)
			.describedAs("Use /r and /reply");
	public static final MejtaPermission TELEPORT_BYPASS = MejtaPermission.of("mejtaqol.teleport.bypass", PermissionLevel.MODERATORS)
			.describedAs("Teleport without waiting, without cooldowns and while in combat (only while op_bypass.enabled is on)");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtaqol.admin", PermissionLevel.ADMINS)
			.describedAs("Use /mejtaadmin mejtaqol status");

	public static final MejtaPermission[] ALL = {TPA, TPAHERE, HOME, HOMES_EXTRA, HOMES_OTHERS, BACK, SPAWN, SETSPAWN, WARP, WARPS_MANAGE, RTP,
			ENDERCHEST, WORKBENCH, ANVIL, STONECUTTER, GRINDSTONE, SMITHING, LOOM, CARTOGRAPHY, TRASH, AFK, AFK_KICK_EXEMPT, REPLY,
			TELEPORT_BYPASS, ADMIN};

	private QolPermissions() {
	}
}
