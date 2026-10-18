package com.mejta.mejtadiscord;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * MejtaDiscord's permission nodes. Setting webhook URLs and the bot token additionally needs MejtaCore's
 * {@code mejtacore.config.secrets} (owners by default).
 */
public final class DiscordPermissions {
	public static final MejtaPermission STATUS = MejtaPermission.of("mejtadiscord.status", PermissionLevel.MODERATORS)
			.describedAs("See the Discord connection status with /mejtaadmin mejtadiscord status");
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtadiscord.admin", PermissionLevel.ADMINS)
			.describedAs("Send test messages, reconnect the bot, and receive MejtaDiscord's own warnings in chat");

	public static final MejtaPermission[] ALL = {STATUS, ADMIN};

	private DiscordPermissions() {
	}
}
