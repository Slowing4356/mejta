package com.mejta.mejtacore.core;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** MejtaCore's own settings, stored in {@code config/mejta/mejtacore.json}. */
public final class CoreSettings {
	public enum MenuSound {
		OFF,
		QUIET,
		NORMAL
	}

	public static final Setting<Boolean> DEBUG_LOGGING = Setting.bool("debug_logging", false)
			.describedAs("Log extra diagnostic information at INFO level");
	public static final Setting<Boolean> SHOW_ADDON_IDS = Setting.bool("show_addon_ids", false)
			.describedAs("Show addon IDs and sources in menu tooltips");
	public static final Setting<MenuSound> MENU_SOUND = Setting.enumeration("menu_sound", MenuSound.NORMAL)
			.describedAs("Click sound when using MejtaCore menus");
	public static final Setting<Integer> MENU_ROWS = Setting.integer("menu_rows", 6, 3, 6)
			.describedAs("Height of MejtaCore menus in rows (3-6)");

	public static final Setting<Boolean> IN_GAME_NOTIFICATIONS = Setting.bool("in_game_notifications", true)
			.describedAs("Show addon notifications (e.g. alerts) in chat to players with the required permission");

	public static final Setting<Boolean> NOTIFICATIONS_FILE_LOG = Setting.bool("notifications.file_log", true)
			.describedAs("Write every addon notification (alerts, kicks, config changes) to mejta-data/mejtacore/notifications.jsonl; works without any Discord addon");

	/** @since 1.3.0 */
	public static final Setting<String> DISABLED_COMMANDS = Setting.string("commands.disabled", "")
			.validatedBy(text -> text.length() <= 512 && text.matches("[a-z0-9_,/ -]*"))
			.describedAs("Top-level addon commands not to register, comma-separated (e.g. \"vanish, freeze\"). "
					+ "They stay available as /mejtaadmin <addon> <command>. Takes effect after /reload or a restart.");

	/** @since 1.5.0 */
	public static final Setting<Integer> AFK_AUTO_MINUTES = Setting.integer("afk.auto_minutes", 5, 0, 120)
			.describedAs("A player idle this many minutes becomes AFK, for every addon that uses AFK status. 0 = never automatically");
	/** @since 1.5.0 */
	public static final Setting<Integer> COMBAT_TAG_SECONDS = Setting.integer("combat.tag_seconds", 10, 0, 300)
			.describedAs("After hurting or being hurt by a player, both are in combat this many seconds (addons refuse teleports, AFK protection and the like). 0 = off");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(DEBUG_LOGGING, SHOW_ADDON_IDS, MENU_SOUND, MENU_ROWS, IN_GAME_NOTIFICATIONS, NOTIFICATIONS_FILE_LOG,
					DISABLED_COMMANDS, AFK_AUTO_MINUTES, COMBAT_TAG_SECONDS)
			.build();

	private CoreSettings() {
	}
}
