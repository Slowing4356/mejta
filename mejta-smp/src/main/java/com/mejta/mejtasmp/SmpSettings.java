package com.mejta.mejtasmp;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtasmp.util.Templates;

/**
 * MejtaSMP's settings, in {@code config/mejta/mejtasmp.json}. Keys are stable. Each feature's keys
 * share a prefix, so the settings menu shows one group per feature.
 */
public final class SmpSettings {
	/** What the sidebar shows a player who has not chosen yet. */
	public enum SidebarView {
		OFF,
		MY_STATS,
		TOP_KILLS,
		TOP_PLAYTIME
	}

	/** The score shown next to names in the player list. */
	public enum TabScore {
		NONE,
		KILLS,
		DEATHS,
		HEALTH
	}

	/** When a pile of dropped items shows its size above it. */
	public enum ShowCount {
		NEVER,
		STACKED,
		ALWAYS
	}

	/** The locator bar game rule. */
	public enum LocatorBar {
		VANILLA,
		SHOWN,
		HIDDEN
	}

	// ---------------------------------------------------------------- AFK protection
	public static final Setting<Boolean> AFK_PROTECTION = Setting.bool("afk_protection.enabled", true)
			.describedAs("AFK players (MejtaCore's AFK status) cannot be hurt by other players, unless they are in combat");
	public static final Setting<Boolean> AFK_PROTECTION_MOBS = Setting.bool("afk_protection.from_mobs", false)
			.describedAs("Also protect AFK players from mobs (falling, lava and the like still hurt)");
	public static final Setting<Boolean> AFK_PROTECTION_NOTICE = Setting.bool("afk_protection.notify_attacker", true)
			.describedAs("Tell a player who hits an AFK player that they are protected");

	// ---------------------------------------------------------------- scoreboard
	public static final Setting<Boolean> SIDEBAR_ENABLED = Setting.bool("scoreboard.sidebar", true)
			.describedAs("The sidebar with stats or a top list; each player picks theirs with /sb");
	public static final Setting<SidebarView> SIDEBAR_DEFAULT = Setting.enumeration("scoreboard.default_view", SidebarView.MY_STATS)
			.describedAs("What the sidebar shows a player who has not picked a view yet");
	public static final Setting<String> SIDEBAR_TITLE = Setting.string("scoreboard.title", "&6&lSMP")
			.validatedBy(text -> Templates.valid(text, 48))
			.describedAs("Title of the sidebar. & colour codes allowed");
	public static final Setting<TabScore> TAB_SCORE = Setting.enumeration("scoreboard.tab_list", TabScore.NONE)
			.describedAs("Score shown next to every name in the player list (Tab)");
	public static final Setting<Boolean> LEADERBOARD = Setting.bool("scoreboard.leaderboard", true)
			.describedAs("/leaderboard: top players by kills, deaths, play time, mob kills and best kill streak");

	// ---------------------------------------------------------------- XP
	public static final Setting<Boolean> XP_CLUMP = Setting.bool("xp.clump", true)
			.describedAs("Experience orbs close to each other merge into one, whatever their value (less lag)");
	public static final Setting<Double> XP_CLUMP_RADIUS = Setting.decimal("xp.clump_radius", 2.0, 0.5, 8.0)
			.describedAs("How close (in blocks) orbs must be to merge");
	public static final Setting<Boolean> XP_INSTANT = Setting.bool("xp.instant_pickup", true)
			.describedAs("A player takes all touching experience at once instead of one orb every few ticks. Mending works as usual");

	// ---------------------------------------------------------------- drops
	public static final Setting<Double> DROPS_MERGE_RADIUS = Setting.decimal("drops.merge_radius", 1.5, 0.5, 8.0)
			.describedAs("How close (in blocks) equal dropped items must be to merge. Vanilla: 0.5");
	public static final Setting<Boolean> DROPS_STACK = Setting.bool("drops.stack_beyond_max", true)
			.experimental()
			.describedAs("Dropped items merge into piles bigger than a stack (e.g. 1,000 cobblestone as one item), so farms cause less lag. Picked up a stack at a time");
	public static final Setting<Integer> DROPS_MAX_PILE = Setting.integer("drops.max_pile", 50_000, 64, 1_000_000)
			.describedAs("Most items one pile on the ground may hold");
	public static final Setting<ShowCount> DROPS_SHOW_COUNT = Setting.enumeration("drops.show_count", ShowCount.STACKED)
			.describedAs("Show the size of a pile above it: never, only for piles bigger than a stack, or always");

	// ---------------------------------------------------------------- villagers
	public static final Setting<Boolean> INFINITE_TRADING = Setting.bool("villagers.infinite_trading", true)
			.describedAs("Villager and wandering trader trades never run out");

	// ---------------------------------------------------------------- sleep
	public static final Setting<Integer> SLEEP_PERCENTAGE = Setting.integer("sleep.percentage", 0, -1, 100)
			.describedAs("Percent of players who must sleep to skip the night. 0 = one player is enough. -1 = leave the game rule as it is");

	// ---------------------------------------------------------------- dimensions
	public static final Setting<Boolean> NETHER_OPEN = Setting.bool("dimensions.nether", true)
			.describedAs("Portals may take players and entities to the Nether");
	public static final Setting<Boolean> END_OPEN = Setting.bool("dimensions.end", true)
			.describedAs("Portals may take players and entities to the End");
	public static final Setting<LocatorBar> LOCATOR_BAR = Setting.enumeration("dimensions.locator_bar", LocatorBar.HIDDEN)
			.describedAs("The locator bar that shows where other players are: shown, hidden, or left as the game rule says");

	// ---------------------------------------------------------------- anti-alt
	public static final Setting<Boolean> ANTIALT = Setting.bool("antialt.enabled", true)
			.describedAs("Limit how many accounts may play from one internet address. Addresses are stored only as salted hashes, never shown or logged");
	public static final Setting<Integer> ANTIALT_MAX_ONLINE = Setting.integer("antialt.max_online", 2, 1, 20)
			.describedAs("Accounts that may be online at once from one address");
	public static final Setting<Integer> ANTIALT_MAX_ACCOUNTS = Setting.integer("antialt.max_accounts", 0, 0, 50)
			.describedAs("Different accounts that may ever join from one address. 0 = no limit");
	public static final Setting<String> ANTIALT_MESSAGE = Setting.string("antialt.kick_message", "Too many accounts from your connection.")
			.validatedBy(text -> Templates.valid(text, 200))
			.describedAs("Shown to a player who is refused. & colour codes allowed");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(AFK_PROTECTION, AFK_PROTECTION_MOBS, AFK_PROTECTION_NOTICE,
					SIDEBAR_ENABLED, SIDEBAR_DEFAULT, SIDEBAR_TITLE, TAB_SCORE, LEADERBOARD,
					XP_CLUMP, XP_CLUMP_RADIUS, XP_INSTANT,
					DROPS_MERGE_RADIUS, DROPS_STACK, DROPS_MAX_PILE, DROPS_SHOW_COUNT,
					INFINITE_TRADING,
					SLEEP_PERCENTAGE,
					NETHER_OPEN, END_OPEN, LOCATOR_BAR,
					ANTIALT, ANTIALT_MAX_ONLINE, ANTIALT_MAX_ACCOUNTS, ANTIALT_MESSAGE)
			.build();

	private SmpSettings() {
	}
}
