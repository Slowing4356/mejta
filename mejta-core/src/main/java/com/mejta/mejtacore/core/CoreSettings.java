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

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(DEBUG_LOGGING, SHOW_ADDON_IDS, MENU_SOUND, MENU_ROWS, IN_GAME_NOTIFICATIONS, NOTIFICATIONS_FILE_LOG)
			.build();

	private CoreSettings() {
	}
}
