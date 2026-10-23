package com.mejta.mejtamarket.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class BotPolicyTest {
	@Test
	void buyingFromTheBotAndSellingBackAlwaysLoses() {
		for (long ref = 1; ref < 5_000; ref += 7) {
			for (double buy : new double[] {0.10, 0.75, 0.95}) {
				for (double sell : new double[] {1.05, 1.15, 5.0}) {
					assertTrue(BotPolicy.buyPrice(ref, buy) < BotPolicy.sellPrice(ref, buy, sell), ref + " " + buy + " " + sell);
				}
			}
		}
	}

	@Test
	void activityFadesWithPlayers() {
		assertEquals(1.0, BotPolicy.activity(2, 3, 10));
		assertEquals(0.5, BotPolicy.activity(6, 2, 10), 1e-9);
		assertEquals(0.0, BotPolicy.activity(10, 3, 10));
	}

	@Test
	void quantityStaysAffordableAndSmall() {
		RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
		for (int i = 0; i < 1000; i++) {
			int q = BotPolicy.quantity(r, 75, 10_000, 64);
			assertTrue(q >= 1 && q * 75L <= 1_000 + 75 && q <= 64, "q=" + q);
		}
		assertEquals(0, BotPolicy.quantity(r, 100, 99, 64));
	}

	@Test
	void choosesOnlyPossibleActions() {
		RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
		for (int i = 0; i < 200; i++) {
			assertEquals(BotPolicy.Action.PLACE_ORDER, BotPolicy.choose(r, false, false, false, true));
		}
		assertEquals(null, BotPolicy.choose(r, false, false, false, false));
	}
}
