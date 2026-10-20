package com.mejta.mejtasmp.scoreboard;

import java.util.ArrayList;
import java.util.List;

import com.mejta.mejtasmp.SmpSettings.SidebarView;
import com.mejta.mejtasmp.stats.PlayerStats;
import com.mejta.mejtasmp.stats.Stat;

/** The text of each sidebar view, with section-sign colour codes. Kept apart from packets for unit tests. */
public final class SidebarLines {
	/** Lines a sidebar shows at most (the game shows 15). */
	public static final int MAX_LINES = 15;
	static final int TOP_SIZE = 10;

	private SidebarLines() {
	}

	public static List<String> of(SidebarView view, PlayerStats me, List<PlayerStats> top, int online) {
		List<String> lines = new ArrayList<>();
		switch (view) {
			case OFF -> {
				return lines;
			}
			case MY_STATS -> {
				lines.add("\u00a77Kills: \u00a7f" + me.kills());
				lines.add("\u00a77Deaths: \u00a7f" + me.deaths());
				lines.add("\u00a77K/D: \u00a7f" + me.kd());
				lines.add("\u00a77Kill streak: \u00a7f" + me.streak() + " \u00a77(best " + me.bestStreak() + ")");
				lines.add("\u00a77Mob kills: \u00a7f" + me.mobKills());
				lines.add("\u00a77Play time: \u00a7f" + PlayerStats.formatTicks(me.playTicks()));
			}
			case TOP_KILLS -> topLines(lines, "Top kills", Stat.KILLS, top);
			case TOP_PLAYTIME -> topLines(lines, "Top play time", Stat.PLAY_TIME, top);
		}
		lines.add(" ");
		lines.add("\u00a77Online: \u00a7f" + online);
		return lines.size() > MAX_LINES ? List.copyOf(lines.subList(0, MAX_LINES)) : lines;
	}

	private static void topLines(List<String> lines, String heading, Stat stat, List<PlayerStats> top) {
		lines.add("\u00a7e" + heading);
		if (top.isEmpty()) {
			lines.add("\u00a78Nobody yet");
		}
		for (int i = 0; i < top.size() && i < TOP_SIZE; i++) {
			PlayerStats s = top.get(i);
			lines.add("\u00a76" + (i + 1) + ". \u00a7f" + s.name() + " \u00a77" + stat.format(s));
		}
	}

	/** The stat a top view ranks by, or null for views that are not a top list. */
	public static Stat rankedBy(SidebarView view) {
		return switch (view) {
			case TOP_KILLS -> Stat.KILLS;
			case TOP_PLAYTIME -> Stat.PLAY_TIME;
			default -> null;
		};
	}
}
