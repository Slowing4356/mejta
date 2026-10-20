package com.mejta.mejtautils;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** MejtaUtils' settings, in {@code config/mejta/mejtautils.json}. Keys are stable. */
public final class UtilsSettings {
	public enum Indicator {
		BOSS_BAR,
		ACTION_BAR,
		BOTH
	}

	public enum LogoutAction {
		NONE,
		NOTIFY_STAFF
	}

	public enum StaffGameMode {
		ADVENTURE,
		CREATIVE
	}

	// ---------------------------------------------------------------- invsee / ecsee
	public static final Setting<Integer> INVSEE_REFRESH_TICKS = Setting.integer("invsee.refresh_ticks", 10, 1, 200)
			.describedAs("How often the info row of /invsee and the status panel refresh, in ticks");
	public static final Setting<Boolean> OFFLINE_VIEW = Setting.bool("invsee.offline.enabled", true)
			.describedAs("Allow viewing offline players' saved inventories and ender chests (read-only)");
	public static final Setting<Boolean> OFFLINE_EDIT = Setting.bool("invsee.offline.edit", false).experimental()
			.describedAs("Allow queueing changes to offline players' inventories. Changes are applied when the player next joins, "
					+ "never by writing their save file. See the README before enabling.");
	public static final Setting<Integer> OFFLINE_BACKUPS = Setting.integer("invsee.offline.backups", 5, 1, 20)
			.describedAs("Inventory backups kept per player before a queued offline change is applied");

	// ---------------------------------------------------------------- vanish
	public static final Setting<Boolean> VANISH_PICKUP = Setting.bool("vanish.default.item_pickup", false)
			.describedAs("Default for new staff: pick up items and XP while vanished");
	public static final Setting<Boolean> VANISH_MOB_TARGETING = Setting.bool("vanish.default.mob_targeting", false)
			.describedAs("Default for new staff: mobs may target you while vanished");
	public static final Setting<Boolean> VANISH_NIGHT_VISION = Setting.bool("vanish.default.night_vision", true)
			.describedAs("Default for new staff: night vision while vanished");
	public static final Setting<Double> VANISH_FLIGHT_SPEED = Setting.decimal("vanish.default.flight_speed", 1.0, 1.0, 4.0)
			.describedAs("Default for new staff: flight speed multiplier while vanished (1 = vanilla)");
	public static final Setting<Boolean> VANISH_SILENT_CONTAINERS = Setting.bool("vanish.default.silent_containers", true)
			.describedAs("Default for new staff: open chests, barrels, shulker boxes and ender chests without animation or sound");
	public static final Setting<Boolean> VANISH_PERSIST = Setting.bool("vanish.persist_across_relog", true)
			.describedAs("Rejoin vanished (with no join message) after logging out or a restart");
	public static final Setting<Boolean> VANISH_HIDE_SERVER_LIST = Setting.bool("vanish.hide_from_server_list", true)
			.describedAs("Leave vanished staff out of the player count and sample in the multiplayer server list");
	public static final Setting<Boolean> VANISH_FAKE_PRESENCE = Setting.bool("vanish.fake_presence.enabled", false)
			.describedAs("Allow /vanish fakequit and /vanish fakejoin (needs mejtautils.vanish.fake)");
	public static final Setting<Boolean> VANISH_BLOCK_GAME_EVENTS = Setting.bool("vanish.block_game_events", true).experimental()
			.describedAs("Vanished staff trigger no sculk sensors, shriekers or Warden vibrations");
	public static final Setting<Boolean> VANISH_FILTER_SOUNDS = Setting.bool("vanish.filter_sounds", true).experimental()
			.describedAs("Hide sounds and block-break particles caused by vanished staff from other players");

	// ---------------------------------------------------------------- freeze
	public static final Setting<Boolean> FREEZE_MOVEMENT = Setting.bool("freeze.block_movement", true)
			.describedAs("Frozen players cannot move (they can still look around)");
	public static final Setting<Boolean> FREEZE_DROP = Setting.bool("freeze.block_item_drop", true)
			.describedAs("Frozen players cannot drop items");
	public static final Setting<Boolean> FREEZE_BREAK = Setting.bool("freeze.block_block_break", true)
			.describedAs("Frozen players cannot break blocks");
	public static final Setting<Boolean> FREEZE_PLACE = Setting.bool("freeze.block_block_place", true)
			.describedAs("Frozen players cannot place blocks or use items on blocks");
	public static final Setting<Boolean> FREEZE_INTERACT = Setting.bool("freeze.block_interact", true)
			.describedAs("Frozen players cannot use items or interact with entities");
	public static final Setting<Boolean> FREEZE_ATTACK = Setting.bool("freeze.block_attack", true)
			.describedAs("Frozen players cannot attack");
	public static final Setting<Boolean> FREEZE_COMMANDS = Setting.bool("freeze.block_commands", true)
			.describedAs("Frozen players can only use the commands in freeze.allowed_commands");
	public static final Setting<String> FREEZE_ALLOWED_COMMANDS = Setting.string("freeze.allowed_commands", "msg,tell,w,r")
			.validatedBy(text -> text.length() <= 256 && text.matches("[a-z0-9_:, -]*"))
			.describedAs("Commands a frozen player may still use, comma-separated (chat is always allowed)");
	public static final Setting<Boolean> FREEZE_INVULNERABLE = Setting.bool("freeze.invulnerable", false)
			.describedAs("Protect frozen players from all damage and hunger");
	public static final Setting<Indicator> FREEZE_INDICATOR = Setting.enumeration("freeze.indicator", Indicator.BOSS_BAR)
			.describedAs("How a frozen player is shown that they are frozen");
	public static final Setting<String> FREEZE_MESSAGE = Setting.string("freeze.message",
			"You have been frozen by staff. Please wait; staff will contact you in chat.")
			.validatedBy(text -> !text.isBlank() && text.length() <= 200)
			.describedAs("Text of the freeze indicator");
	public static final Setting<LogoutAction> FREEZE_ON_LOGOUT = Setting.enumeration("freeze.on_logout", LogoutAction.NOTIFY_STAFF)
			.describedAs("What happens when a frozen player logs out. There is deliberately no punishment option: logging out is not proof of cheating.");

	// ---------------------------------------------------------------- staff mode
	public static final Setting<StaffGameMode> STAFF_GAMEMODE = Setting.enumeration("staffmode.gamemode", StaffGameMode.ADVENTURE)
			.describedAs("Game mode in staff mode. Items gained in staff mode are discarded when it ends.");
	public static final Setting<Boolean> STAFF_FLIGHT = Setting.bool("staffmode.flight", true)
			.describedAs("Allow flight in staff mode");
	public static final Setting<Boolean> STAFF_VANISH = Setting.bool("staffmode.vanish", true)
			.describedAs("Vanish when entering staff mode");
	public static final Setting<Boolean> STAFF_COMPASS = Setting.bool("staffmode.tools.compass", true)
			.describedAs("Give the random-player teleport compass");
	public static final Setting<Boolean> STAFF_WAND = Setting.bool("staffmode.tools.inspect_wand", true)
			.describedAs("Give the inspection wand (right-click a player: inventory; shift-right-click: status)");
	public static final Setting<Boolean> STAFF_FREEZE_ROD = Setting.bool("staffmode.tools.freeze_rod", true)
			.describedAs("Give the freeze rod (right-click a player twice within 3 s to freeze or unfreeze)");
	public static final Setting<Integer> STAFF_HISTORY = Setting.integer("staffmode.session_history", 10, 1, 100)
			.describedAs("Finished staff-mode sessions kept per staff member, for recovery");

	// ---------------------------------------------------------------- operators
	public static final Setting<Boolean> OP_BYPASS = Setting.bool("op_bypass.enabled", false)
			.describedAs("Let mejtautils.freeze.exempt protect from being frozen (owners by default). Off: it does nothing;"
					+ " players with mejtautils.protected (owners by default) can still only be frozen by staff who have it too");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(OP_BYPASS,
					INVSEE_REFRESH_TICKS, OFFLINE_VIEW, OFFLINE_EDIT, OFFLINE_BACKUPS,
					VANISH_PICKUP, VANISH_MOB_TARGETING, VANISH_NIGHT_VISION, VANISH_FLIGHT_SPEED, VANISH_SILENT_CONTAINERS,
					VANISH_PERSIST, VANISH_HIDE_SERVER_LIST, VANISH_FAKE_PRESENCE, VANISH_BLOCK_GAME_EVENTS, VANISH_FILTER_SOUNDS,
					FREEZE_MOVEMENT, FREEZE_DROP, FREEZE_BREAK, FREEZE_PLACE, FREEZE_INTERACT, FREEZE_ATTACK, FREEZE_COMMANDS,
					FREEZE_ALLOWED_COMMANDS, FREEZE_INVULNERABLE, FREEZE_INDICATOR, FREEZE_MESSAGE, FREEZE_ON_LOGOUT,
					STAFF_GAMEMODE, STAFF_FLIGHT, STAFF_VANISH, STAFF_COMPASS, STAFF_WAND, STAFF_FREEZE_ROD, STAFF_HISTORY)
			.build();

	private UtilsSettings() {
	}
}
