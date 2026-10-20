package com.mejta.mejtasmp.scoreboard;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import com.mejta.mejtasmp.SmpSettings.TabScore;
import com.mejta.mejtasmp.stats.StatBook;

/**
 * {@code scoreboard.tab_list}: a score next to every name in the player list, as an objective
 * ({@code mejtasmp_tab}) on the server's scoreboard in the list slot. {@code NONE} removes it.
 */
public final class TabList {
	static final String OBJECTIVE = "mejtasmp_tab";

	private TabList() {
	}

	/** Creates, replaces or removes the objective to match {@code mode}. Returns it, or null. */
	public static Objective apply(MinecraftServer server, TabScore mode) {
		Scoreboard board = server.getScoreboard();
		Objective existing = board.getObjective(OBJECTIVE);
		if (mode == TabScore.NONE) {
			if (existing != null) {
				board.removeObjective(existing);
			}
			return null;
		}
		ObjectiveCriteria criteria = mode == TabScore.HEALTH ? ObjectiveCriteria.HEALTH : ObjectiveCriteria.DUMMY;
		Component title = Component.literal(switch (mode) {
			case KILLS -> "Kills";
			case DEATHS -> "Deaths";
			default -> "Health";
		});
		if (existing != null && (existing.getCriteria() != criteria || !existing.getDisplayName().equals(title))) {
			board.removeObjective(existing);
			existing = null;
		}
		if (existing == null) {
			existing = board.addObjective(OBJECTIVE, criteria, title, criteria.getDefaultRenderType(), false, null);
		}
		if (board.getDisplayObjective(DisplaySlot.LIST) != existing) {
			board.setDisplayObjective(DisplaySlot.LIST, existing);
		}
		return existing;
	}

	/** Sets every online player's score (health too, so it is right before it first changes). */
	public static void update(MinecraftServer server, TabScore mode, StatBook book) {
		Objective objective = server.getScoreboard().getObjective(OBJECTIVE);
		if (objective == null || mode == TabScore.NONE) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			int value = switch (mode) {
				case KILLS -> book.get(player.getUUID()).map(s -> s.kills()).orElse(0);
				case DEATHS -> book.get(player.getUUID()).map(s -> s.deaths()).orElse(0);
				default -> Mth.ceil(player.getHealth());
			};
			var score = server.getScoreboard().getOrCreatePlayerScore(player, objective);
			if (score.get() != value) {
				score.set(value);
			}
		}
	}
}
