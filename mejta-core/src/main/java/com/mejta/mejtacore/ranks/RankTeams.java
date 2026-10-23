package com.mejta.mejtacore.ranks;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.rank.Rank;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.ServerHolder;

/**
 * Shows rank prefixes before names in chat, the tab list and above heads, with one scoreboard team per rank
 * ({@code ranks.show_prefix}). The team names sort Gold first in the tab list. A player who is already in a team that
 * is not one of these keeps it and shows no prefix. Teammates do not see each other while invisible, so a rank gives
 * no advantage.
 */
public final class RankTeams {
	private static final String PREFIX = "mejta_rank_";

	private RankTeams() {
	}

	public static void register(ConfigHandle coreConfig) {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> refreshAll(server, coreConfig));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> apply(server, coreConfig, handler.player));
		coreConfig.addChangeListener(() -> ServerHolder.current().ifPresent(server -> server.execute(() -> refreshAll(server, coreConfig))));
		Ranks.get().addRankListener((player, before, after) -> ServerHolder.current().ifPresent(server -> {
			ServerPlayer online = server.getPlayerList().getPlayer(player);
			if (online != null) {
				apply(server, coreConfig, online);
			}
		}));
	}

	/** Team name per rank: {@code mejta_rank_1_gold} sorts before {@code mejta_rank_3_bronze}. */
	static String teamName(Rank rank) {
		return PREFIX + (Rank.values().length - rank.ordinal()) + "_" + rank.id();
	}

	private static void refreshAll(MinecraftServer server, ConfigHandle config) {
		ServerScoreboard scoreboard = server.getScoreboard();
		boolean show = config.get(CoreSettings.RANKS_SHOW_PREFIX);
		for (Rank rank : Rank.values()) {
			if (rank == Rank.MEMBER) {
				continue;
			}
			PlayerTeam team = scoreboard.getPlayerTeam(teamName(rank));
			if (team == null && show) {
				team = scoreboard.addPlayerTeam(teamName(rank));
			}
			if (team != null) {
				team.setPlayerPrefix(Ranks.get().prefix(rank).copy().append(Component.literal(" ")));
				team.setSeeFriendlyInvisibles(false);
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			apply(server, config, player);
		}
	}

	static void apply(MinecraftServer server, ConfigHandle config, ServerPlayer player) {
		ServerScoreboard scoreboard = server.getScoreboard();
		String name = player.getScoreboardName();
		PlayerTeam current = scoreboard.getPlayersTeam(name);
		if (current != null && !current.getName().startsWith(PREFIX)) {
			return; // another mod's or the server's own team
		}
		Rank rank = Ranks.get().rank(player.getUUID());
		PlayerTeam target = null;
		if (config.get(CoreSettings.RANKS_SHOW_PREFIX) && rank != Rank.MEMBER) {
			target = scoreboard.getPlayerTeam(teamName(rank));
			if (target == null) {
				target = scoreboard.addPlayerTeam(teamName(rank));
				target.setPlayerPrefix(Ranks.get().prefix(rank).copy().append(Component.literal(" ")));
				target.setSeeFriendlyInvisibles(false);
			}
		}
		if (current == target) {
			return;
		}
		if (current != null) {
			scoreboard.removePlayerFromTeam(name, current);
		}
		if (target != null) {
			scoreboard.addPlayerToTeam(name, target);
		}
	}
}
