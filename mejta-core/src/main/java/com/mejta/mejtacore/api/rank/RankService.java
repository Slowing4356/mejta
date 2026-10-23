package com.mejta.mejtacore.api.rank;

import java.util.UUID;

import net.minecraft.network.chat.Component;

/**
 * Per-player ranks, shared by every addon. Obtain it with {@code MejtaCoreAPI.ranks()}.
 *
 * <p>Staff and the console set ranks with {@code /mejtaadmin rank set <player> <rank>}, also for offline players, and
 * a donation store or a crate reward can run the same command. A player without a stored rank is {@link Rank#MEMBER}.
 * If {@code ranks.playtime_hours} (in {@code mejtacore.json}) is above 0, a Member who has played that long earns
 * {@link Rank#BRONZE} by themselves.
 *
 * <p><b>Ranks and permissions.</b> Besides its operator level, a permission node can be given to every player from a
 * rank up ({@code MejtaPermission#grantedFrom}, or {@code mejtacore.rankperms.json}). {@code MejtaCoreAPI.hasPermission}
 * checks both, so a perk command needs no code for ranks.
 *
 * <p><b>Values per rank.</b> For limits such as homes, an addon keeps one setting per rank and picks the player's with
 * {@link #byRank}.
 *
 * <p>Reads are thread-safe. Ranks are cached after their first read.
 *
 * @since API version 8
 */
public interface RankService {
	/** The player's rank; {@link Rank#MEMBER} if none is stored. The player may be offline. */
	Rank rank(UUID player);

	/** Whether the player's rank is {@code rank} or higher. */
	default boolean atLeast(UUID player, Rank rank) {
		return rank(player).atLeast(rank);
	}

	/** The value for the player's rank, e.g. {@code byRank(id, 3, 5, 6, 7)} homes. */
	default <T> T byRank(UUID player, T member, T bronze, T silver, T gold) {
		return switch (rank(player)) {
			case MEMBER -> member;
			case BRONZE -> bronze;
			case SILVER -> silver;
			case GOLD -> gold;
		};
	}

	/** The rank's prefix for chat and the tab list, as set in {@code mejtacore.json}; empty for {@link Rank#MEMBER}. */
	Component prefix(Rank rank);

	/** Hours of playtime after which a Member earns {@link Rank#BRONZE}; {@code 0} if that is off. */
	int playtimeHours();

	/** Receives every rank change from now on. */
	void addRankListener(RankListener listener);
}
