package com.mejta.mejtacrates.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class RollTest {
	private static final List<Reward> REWARDS = List.of(Reward.ofMoney(1, 1), Reward.ofMoney(3, 2), Reward.ofMoney(6, 3));

	@Test
	void pointsLandOnTheirShare() {
		assertEquals(0, Roll.pick(REWARDS, 0.0));
		assertEquals(0, Roll.pick(REWARDS, 0.0999));
		assertEquals(1, Roll.pick(REWARDS, 0.1));
		assertEquals(1, Roll.pick(REWARDS, 0.3999));
		assertEquals(2, Roll.pick(REWARDS, 0.4));
		assertEquals(2, Roll.pick(REWARDS, 0.9999999));
		assertEquals(2, Roll.pick(REWARDS, 1.0), "out of range is clamped");
		assertThrows(IllegalArgumentException.class, () -> Roll.pick(List.of(), 0.5));
	}

	@Test
	void drawsFollowTheWeights() {
		SplittableRandom random = new SplittableRandom(42);
		int[] hits = new int[3];
		for (int i = 0; i < 100_000; i++) {
			hits[Roll.pick(REWARDS, random)]++;
		}
		assertEquals(0.1, hits[0] / 100_000.0, 0.01);
		assertEquals(0.3, hits[1] / 100_000.0, 0.01);
		assertEquals(0.6, hits[2] / 100_000.0, 0.01);
	}

	@Test
	void percentages() {
		assertEquals(0.6, Roll.chance(REWARDS.get(2), REWARDS), 1e-9);
		assertEquals("60%", Roll.percent(0.6));
		assertEquals("12.5%", Roll.percent(0.125));
		assertEquals("0.25%", Roll.percent(0.0025));
		assertEquals("1%", Roll.percent(0.01));
		assertEquals("<0.01%", Roll.percent(0.00001));
	}
}
