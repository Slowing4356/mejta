package com.mejta.mejtasmp.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlayerStatsTest {
	private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

	@Test
	void streaksAndKd() {
		PlayerStats s = PlayerStats.empty(ID, "Steve").killed().killed().killed();
		assertEquals(3, s.streak());
		s = s.died().killed();
		assertEquals(1, s.streak());
		assertEquals(3, s.bestStreak());
		assertEquals("4.00", s.withGameStats("Steve", 4, 0, 0, 0).kd(), "no deaths: the kills");
		assertEquals("1.50", s.withGameStats("Steve", 3, 2, 0, 0).kd());
	}

	@Test
	void jsonRoundTripAndTime() {
		PlayerStats s = new PlayerStats(ID, "Alex", 5, 2, 72_000 * 3 + 1200 * 5, 40, 1, 4);
		assertEquals(s, PlayerStats.fromJson(ID, s.toJson()));
		assertEquals("3h 05m", PlayerStats.formatTicks(s.playTicks()));
		assertEquals("45m", PlayerStats.formatTicks(1200 * 45));
	}

	@Test
	void rankingHighestFirstThenByName() {
		List<PlayerStats> list = new ArrayList<>(List.of(stats("b", 3), stats("a", 3), stats("c", 9)));
		list.sort(Stat.KILLS.ranking());
		assertEquals(List.of("c", "a", "b"), list.stream().map(PlayerStats::name).toList());
	}

	private static PlayerStats stats(String name, int kills) {
		return new PlayerStats(UUID.nameUUIDFromBytes(name.getBytes()), name, kills, 0, 0, 0, 0, 0);
	}
}
