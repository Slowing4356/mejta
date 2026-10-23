package com.mejta.mejtaqol;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.rank.Rank;
import com.mejta.mejtaqol.rtp.RtpMath;
import com.mejta.mejtaqol.util.Names;
import com.mejta.mejtaqol.util.Templates;

/**
 * MejtaQoL's settings, in {@code config/mejta/mejtaqol.json}. Keys are stable. Every feature has its
 * own {@code <feature>.enabled} switch; a disabled feature's commands disappear from the command list.
 */
public final class QolSettings {
	// ---------------------------------------------------------------- operators
	public static final Setting<Boolean> OP_BYPASS = Setting.bool("op_bypass.enabled", false)
			.describedAs("Let mejtaqol.teleport.bypass skip the teleport wait, cooldowns, combat lock and dimension rule, and mejtaqol.afk.kick_exempt"
					+ " prevent the idle kick (both operators by default). Off: operators follow the rules like everyone else");

	// ---------------------------------------------------------------- teleports (shared)
	public static final Setting<Integer> WARMUP_SECONDS = Setting.integer("teleport.warmup_seconds", 3, 0, 60)
			.describedAs("Seconds a player must wait (standing still) before a teleport happens. 0 = instant");
	public static final Setting<Boolean> CANCEL_ON_MOVE = Setting.bool("teleport.cancel_on_move", true)
			.describedAs("Moving during the wait cancels the teleport (looking around is allowed)");
	public static final Setting<Boolean> CANCEL_ON_DAMAGE = Setting.bool("teleport.cancel_on_damage", true)
			.describedAs("Taking damage during the wait cancels the teleport");
	public static final Setting<Boolean> CROSS_DIMENSION = Setting.bool("teleport.cross_dimension", true)
			.describedAs("Allow teleports into another dimension (homes, warps, /tpa, /back, /spawn)");

	// ---------------------------------------------------------------- /tpa
	public static final Setting<Boolean> TPA_ENABLED = Setting.bool("tpa.enabled", true)
			.describedAs("/tpa, /tpaccept, /tpdeny, /tpacancel and /tptoggle");
	public static final Setting<Boolean> TPAHERE_ENABLED = Setting.bool("tpa.here_enabled", true)
			.describedAs("/tpahere: ask another player to come to you");
	public static final Setting<Integer> TPA_TIMEOUT = Setting.integer("tpa.timeout_seconds", 60, 10, 600)
			.describedAs("Seconds before an unanswered teleport request expires");
	public static final Setting<Integer> TPA_COOLDOWN = Setting.integer("tpa.cooldown_seconds", 3, 0, 3600)
			.describedAs("Seconds between two teleport requests from the same player");

	// ---------------------------------------------------------------- homes
	public static final Setting<Boolean> HOMES_ENABLED = Setting.bool("homes.enabled", true)
			.describedAs("/sethome, /home, /delhome and /homes");
	public static final Setting<Integer> HOMES_MAX = Setting.integer("homes.max", 3, 0, 100)
			.describedAs("Homes a Member (a player without a rank) may have");
	/** @since 1.3.0 */
	public static final Setting<Integer> HOMES_MAX_BRONZE = Setting.integer("homes.max_bronze", 5, 0, 100)
			.describedAs("Homes a Bronze player may have");
	/** @since 1.3.0 */
	public static final Setting<Integer> HOMES_MAX_SILVER = Setting.integer("homes.max_silver", 6, 0, 100)
			.describedAs("Homes a Silver player may have");
	/** @since 1.3.0 */
	public static final Setting<Integer> HOMES_MAX_GOLD = Setting.integer("homes.max_gold", 7, 0, 100)
			.describedAs("Homes a Gold player may have");
	public static final Setting<Integer> HOMES_MAX_EXTRA = Setting.integer("homes.max_extra", 10, 0, 100)
			.describedAs("Homes a player with mejtaqol.homes.extra may have");
	public static final Setting<Integer> HOMES_COOLDOWN = Setting.integer("homes.cooldown_seconds", 3, 0, 3600)
			.describedAs("Seconds between two /home teleports");
	public static final Setting<String> HOMES_BLOCKED_DIMENSIONS = Setting.string("homes.blocked_dimensions", "")
			.validatedBy(Names::isDimensionList)
			.describedAs("Dimensions where /sethome is refused, comma-separated (e.g. the_end)");
	public static final Setting<Boolean> HOMES_MENU = Setting.bool("homes.menu", true)
			.describedAs("/homes opens a chest menu (off: a clickable list in chat)");

	// ---------------------------------------------------------------- /back
	public static final Setting<Boolean> BACK_ENABLED = Setting.bool("back.enabled", true)
			.describedAs("/back: return to where you were before your last teleport");
	public static final Setting<Boolean> BACK_ON_DEATH = Setting.bool("back.on_death", true)
			.describedAs("/back also returns to where you died");
	public static final Setting<Integer> BACK_COOLDOWN = Setting.integer("back.cooldown_seconds", 5, 0, 3600)
			.describedAs("Seconds between two /back teleports");

	// ---------------------------------------------------------------- /spawn
	public static final Setting<Boolean> SPAWN_ENABLED = Setting.bool("spawn.enabled", true)
			.describedAs("/spawn: teleport to the spawn point set with /setspawn (or the world spawn)");
	public static final Setting<Integer> SPAWN_COOLDOWN = Setting.integer("spawn.cooldown_seconds", 5, 0, 3600)
			.describedAs("Seconds between two /spawn teleports");
	public static final Setting<Boolean> SPAWN_ON_FIRST_JOIN = Setting.bool("spawn.on_first_join", false)
			.describedAs("Put players at the /setspawn point when they join for the first time");

	// ---------------------------------------------------------------- warps
	public static final Setting<Boolean> WARPS_ENABLED = Setting.bool("warps.enabled", true)
			.describedAs("/warp and /warps (staff create them with /setwarp)");
	public static final Setting<Integer> WARPS_COOLDOWN = Setting.integer("warps.cooldown_seconds", 5, 0, 3600)
			.describedAs("Seconds between two /warp teleports");
	public static final Setting<Boolean> WARPS_MENU = Setting.bool("warps.menu", true)
			.describedAs("/warps opens a chest menu (off: a clickable list in chat)");

	// ---------------------------------------------------------------- /rtp
	public static final Setting<Boolean> RTP_ENABLED = Setting.bool("rtp.enabled", true).experimental()
			.describedAs("/rtp: teleport to a random safe place. Each attempt may load or generate a chunk on the server thread");
	public static final Setting<Integer> RTP_MIN_RADIUS = Setting.integer("rtp.min_radius", 500, 0, 1_000_000)
			.describedAs("Smallest distance from the centre, in blocks");
	public static final Setting<Integer> RTP_MAX_RADIUS = Setting.integer("rtp.max_radius", 5000, 16, 1_000_000)
			.describedAs("Largest distance from the centre, in blocks (the world border also limits it)");
	public static final Setting<String> RTP_CENTER = Setting.string("rtp.center", "spawn")
			.validatedBy(text -> RtpMath.parseCenter(text).isPresent())
			.describedAs("Centre of the search: spawn (the world spawn) or x,z (e.g. 0,0)");
	public static final Setting<String> RTP_DIMENSIONS = Setting.string("rtp.dimensions", "minecraft:overworld")
			.validatedBy(Names::isDimensionList)
			.describedAs("Dimensions where /rtp works, comma-separated. The player is moved within their current dimension");
	public static final Setting<Integer> RTP_ATTEMPTS = Setting.integer("rtp.max_attempts", 8, 1, 32)
			.describedAs("Places tried before giving up (one per server tick)");
	public static final Setting<Integer> RTP_COOLDOWN = Setting.integer("rtp.cooldown_seconds", 300, 0, 86_400)
			.describedAs("Seconds between two /rtp uses of a Member (a player without a rank)");
	/** @since 1.3.0 */
	public static final Setting<Integer> RTP_COOLDOWN_BRONZE = Setting.integer("rtp.cooldown_seconds_bronze", 240, 0, 86_400)
			.describedAs("Seconds between two /rtp uses of a Bronze player");
	/** @since 1.3.0 */
	public static final Setting<Integer> RTP_COOLDOWN_SILVER = Setting.integer("rtp.cooldown_seconds_silver", 180, 0, 86_400)
			.describedAs("Seconds between two /rtp uses of a Silver player");
	/** @since 1.3.0 */
	public static final Setting<Integer> RTP_COOLDOWN_GOLD = Setting.integer("rtp.cooldown_seconds_gold", 120, 0, 86_400)
			.describedAs("Seconds between two /rtp uses of a Gold player");

	// ---------------------------------------------------------------- portable containers
	public static final Setting<Boolean> ENDERCHEST_ENABLED = Setting.bool("enderchest.enabled", true)
			.describedAs("/enderchest (/ec): open your own ender chest anywhere");
	public static final Setting<Boolean> WORKBENCH_ENABLED = Setting.bool("workstations.crafting", true)
			.describedAs("/workbench (/craft): a crafting table anywhere");
	public static final Setting<Boolean> ANVIL_ENABLED = Setting.bool("workstations.anvil", true)
			.describedAs("/anvil: an anvil anywhere (it never breaks)");
	public static final Setting<Boolean> STONECUTTER_ENABLED = Setting.bool("workstations.stonecutter", true)
			.describedAs("/stonecutter: a stonecutter anywhere");
	public static final Setting<Boolean> GRINDSTONE_ENABLED = Setting.bool("workstations.grindstone", true)
			.describedAs("/grindstone: a grindstone anywhere");
	public static final Setting<Boolean> SMITHING_ENABLED = Setting.bool("workstations.smithing", true)
			.describedAs("/smithing: a smithing table anywhere");
	public static final Setting<Boolean> LOOM_ENABLED = Setting.bool("workstations.loom", true)
			.describedAs("/loom: a loom anywhere");
	public static final Setting<Boolean> CARTOGRAPHY_ENABLED = Setting.bool("workstations.cartography", true)
			.describedAs("/cartography: a cartography table anywhere");
	public static final Setting<Boolean> TRASH_ENABLED = Setting.bool("trash.enabled", true)
			.describedAs("/trash: a container whose items are deleted when it is closed");

	// ---------------------------------------------------------------- AFK
	public static final Setting<Boolean> AFK_ENABLED = Setting.bool("afk.enabled", true)
			.describedAs("/afk, the AFK broadcast, the [AFK] tag and the idle kick. Who is AFK (also after idling, see afk.auto_minutes in mejtacore.json) is decided by MejtaCore");
	public static final Setting<Boolean> AFK_BROADCAST = Setting.bool("afk.broadcast", true)
			.describedAs("Tell everyone when a player goes AFK or comes back (never for vanished players)");
	public static final Setting<Boolean> AFK_TAB_LIST = Setting.bool("afk.tab_list", true)
			.describedAs("Show [AFK] after AFK players' names in the player list");
	public static final Setting<Integer> AFK_KICK_MINUTES = Setting.integer("afk.kick_minutes", 0, 0, 1440)
			.describedAs("Kick players idle this many minutes (not those with mejtaqol.afk.kick_exempt). 0 = never");
	public static final Setting<String> AFK_KICK_MESSAGE = Setting.string("afk.kick_message", "You were idle for too long.")
			.validatedBy(text -> Templates.valid(text, 200))
			.describedAs("Shown to a player kicked for being idle (& colour codes allowed)");

	// ---------------------------------------------------------------- chat
	public static final Setting<Boolean> REPLY_ENABLED = Setting.bool("reply.enabled", true)
			.describedAs("/r (/reply): answer the last player you exchanged a private message with");
	public static final Setting<Boolean> MOTD_ENABLED = Setting.bool("motd.enabled", false)
			.describedAs("Greet players with motd.text when they join");
	public static final Setting<String> MOTD_TEXT = Setting.string("motd.text", "&6Welcome, {player}! &7Type &f/qol &7to see what you can do.")
			.validatedBy(text -> Templates.valid(text, 256))
			.describedAs("Join greeting. Placeholders: {player}, {online}. & colour codes allowed");
	public static final Setting<String> FIRST_JOIN_TEXT = Setting.string("motd.first_join_broadcast", "&e{player} joined for the first time. Welcome!")
			.validatedBy(text -> Templates.valid(text, 256))
			.describedAs("Told to everyone when a player joins for the first time (if motd.enabled). Placeholders: {player}");
	public static final Setting<Boolean> DEATH_COORDINATES = Setting.bool("death.coordinates", true)
			.describedAs("Tell a player privately where they died");

	/**
	 * Version 2 (1.1.0): the combat lock and automatic AFK moved to MejtaCore ({@code combat.tag_seconds} and
	 * {@code afk.auto_minutes} in mejtacore.json), so their old keys are removed here.
	 */
	public static final ConfigSpec SPEC = ConfigSpec.builder(2)
			.migration(1, data -> {
				data.remove("teleport.combat_seconds");
				data.remove("afk.auto_minutes");
			})
			.add(OP_BYPASS,
					WARMUP_SECONDS, CANCEL_ON_MOVE, CANCEL_ON_DAMAGE, CROSS_DIMENSION,
					TPA_ENABLED, TPAHERE_ENABLED, TPA_TIMEOUT, TPA_COOLDOWN,
					HOMES_ENABLED, HOMES_MAX, HOMES_MAX_BRONZE, HOMES_MAX_SILVER, HOMES_MAX_GOLD, HOMES_MAX_EXTRA, HOMES_COOLDOWN, HOMES_BLOCKED_DIMENSIONS, HOMES_MENU,
					BACK_ENABLED, BACK_ON_DEATH, BACK_COOLDOWN,
					SPAWN_ENABLED, SPAWN_COOLDOWN, SPAWN_ON_FIRST_JOIN,
					WARPS_ENABLED, WARPS_COOLDOWN, WARPS_MENU,
					RTP_ENABLED, RTP_MIN_RADIUS, RTP_MAX_RADIUS, RTP_CENTER, RTP_DIMENSIONS, RTP_ATTEMPTS, RTP_COOLDOWN,
					RTP_COOLDOWN_BRONZE, RTP_COOLDOWN_SILVER, RTP_COOLDOWN_GOLD,
					ENDERCHEST_ENABLED, WORKBENCH_ENABLED, ANVIL_ENABLED, STONECUTTER_ENABLED, GRINDSTONE_ENABLED, SMITHING_ENABLED,
					LOOM_ENABLED, CARTOGRAPHY_ENABLED, TRASH_ENABLED,
					AFK_ENABLED, AFK_BROADCAST, AFK_TAB_LIST, AFK_KICK_MINUTES, AFK_KICK_MESSAGE,
					REPLY_ENABLED, MOTD_ENABLED, MOTD_TEXT, FIRST_JOIN_TEXT, DEATH_COORDINATES)
			.build();

	/** Settings that have one value per rank: the Member setting, then Bronze, Silver and Gold. */
	private static final Map<Setting<Integer>, List<Setting<Integer>>> BY_RANK = Map.of(
			HOMES_MAX, List.of(HOMES_MAX_BRONZE, HOMES_MAX_SILVER, HOMES_MAX_GOLD),
			RTP_COOLDOWN, List.of(RTP_COOLDOWN_BRONZE, RTP_COOLDOWN_SILVER, RTP_COOLDOWN_GOLD));

	/**
	 * The value of {@code setting} for the player's rank (MejtaCore's ranks): for {@link #HOMES_MAX} a Gold player gets
	 * {@link #HOMES_MAX_GOLD}. Settings without values per rank are simply read.
	 */
	public static int forRank(ConfigHandle config, Setting<Integer> setting, UUID player) {
		List<Setting<Integer>> ranked = BY_RANK.get(setting);
		Rank rank = ranked == null ? Rank.MEMBER : MejtaCoreAPI.ranks().rank(player);
		return config.get(rank == Rank.MEMBER ? setting : ranked.get(rank.ordinal() - 1));
	}

	private QolSettings() {
	}
}
