package com.mejta.mejtasmp.scoreboard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtasmp.SmpSettings.SidebarView;
import com.mejta.mejtasmp.stats.PlayerStats;

class SidebarLinesTest {
	private static final PlayerStats ME = new PlayerStats(UUID.randomUUID(), "Me", 7, 2, 20 * 60 * 90, 3, 2, 5);

	@Test
	void myStatsShowEveryNumber() {
		String all = String.join("\n", SidebarLines.of(SidebarView.MY_STATS, ME, List.of(), 4));
		for (String part : List.of("Kills: \u00a7f7", "Deaths: \u00a7f2", "3.50", "best 5", "1h 30m", "Online: \u00a7f4")) {
			assertTrue(all.contains(part), part + " in " + all);
		}
	}

	@Test
	void topListsFitTheSidebar() {
		List<PlayerStats> top = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			top.add(new PlayerStats(UUID.randomUUID(), "p" + i, 20 - i, 0, 0, 0, 0, 0));
		}
		List<String> lines = SidebarLines.of(SidebarView.TOP_KILLS, ME, top, 1);
		assertTrue(lines.size() <= SidebarLines.MAX_LINES);
		assertTrue(lines.get(1).contains("1. \u00a7fp0"));
		assertTrue(SidebarLines.of(SidebarView.OFF, ME, top, 1).isEmpty(), "off: no lines, so the sidebar is hidden");
		assertTrue(String.join("", SidebarLines.of(SidebarView.TOP_PLAYTIME, ME, List.of(), 1)).contains("Nobody yet"));
	}
}
