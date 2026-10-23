package com.mejta.mejtamarket.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BotLedgerTest {
	private static final long DAY = BotLedger.DAY;

	@Test
	void budgetRefillsDailyUpToTheCap() {
		BotLedger l = new BotLedger();
		assertEquals(1000, l.available(0, 1000, 3000));
		assertTrue(l.spend(0, 1000, 3000, 1000));
		assertFalse(l.spend(0, 1000, 3000, 1));
		// Asked every second for half a day: rounding loses nothing.
		for (long t = 0; t <= DAY / 2; t += 1000) {
			l.available(t, 1000, 3000);
		}
		assertEquals(500, l.available(DAY / 2, 1000, 3000));
		assertEquals(3000, l.available(10 * DAY, 1000, 3000));
		assertEquals(1000, l.paidOut());
	}

	@Test
	void stockIsPerItemAndDay() {
		BotLedger l = new BotLedger();
		l.useStock("x", DAY + 5, 60);
		assertEquals(4, l.stockLeft("x", DAY + 10, 64));
		assertEquals(64, l.stockLeft("y", DAY + 10, 64));
		assertEquals(64, l.stockLeft("x", 2 * DAY, 64));
	}

	@Test
	void survivesJson() {
		BotLedger l = new BotLedger();
		l.spend(0, 1000, 3000, 400);
		l.received(250, 5);
		BotLedger copy = BotLedger.fromJson(l.toJson());
		assertEquals(l.toJson(), copy.toJson());
	}
}
