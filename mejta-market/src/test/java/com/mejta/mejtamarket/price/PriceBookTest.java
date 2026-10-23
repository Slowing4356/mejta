package com.mejta.mejtamarket.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PriceBookTest {
	private static final PriceBook.Params PARAMS = new PriceBook.Params(14, 3, 0.10, 2.0);
	private static final long NOW = 100 * PriceBook.DAY;
	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);
	private static final UUID C = new UUID(0, 3);

	@Test
	void listedPriceUntilEnoughDistinctTrades() {
		PriceBook book = new PriceBook();
		// Ten trades between the same two players on one day are one sample.
		for (int i = 0; i < 10; i++) {
			book.record("x", NOW, 500, 1, A, B);
		}
		assertTrue(book.observed("x", NOW, PARAMS).isEmpty());
		assertEquals(100, book.reference("x", NOW, OptionalLong.of(100), PARAMS).getAsLong());
	}

	@Test
	void botTradesNeverCount() {
		PriceBook book = new PriceBook();
		book.record("x", NOW, 900, 1, PriceBook.BOT, A);
		book.record("x", NOW, 900, 1, B, PriceBook.BOT);
		book.record("x", NOW, 900, 1, C, PriceBook.BOT);
		assertTrue(book.observed("x", NOW, PARAMS).isEmpty());
	}

	@Test
	void referenceMovesAtMostTheDailyLimitAndStaysInTheBand() {
		PriceBook book = new PriceBook();
		assertEquals(100, book.reference("x", NOW, OptionalLong.of(100), PARAMS).getAsLong());
		// Players suddenly trade at 10x.
		book.record("x", NOW, 1000, 1, A, B);
		book.record("x", NOW, 1000, 1, B, C);
		book.record("x", NOW, 1000, 1, C, A);
		// Asked every minute for a day: rounding must not add up to more than 10 %.
		long last = 100;
		for (long t = NOW; t <= NOW + PriceBook.DAY; t += 60_000) {
			last = book.reference("x", t, OptionalLong.of(100), PARAMS).getAsLong();
		}
		assertTrue(last >= 105 && last <= 110, "after one day: " + last);
		// Weeks later it is capped at twice the listed price.
		long later = book.reference("x", NOW + 13 * PriceBook.DAY, OptionalLong.of(100), PARAMS).getAsLong();
		assertEquals(200, later);
	}

	@Test
	void trimmedMedianIgnoresOutliers() {
		PriceBook book = new PriceBook();
		UUID[] p = {A, B, C, new UUID(0, 4), new UUID(0, 5)};
		long[] prices = {10, 100, 101, 102, 100_000};
		for (int i = 0; i < prices.length; i++) {
			book.record("x", NOW, prices[i], 1, p[i], p[(i + 1) % p.length]);
		}
		assertEquals(101, book.observed("x", NOW, PARAMS).getAsLong());
	}

	@Test
	void survivesJson() {
		PriceBook book = new PriceBook();
		book.record("x", NOW, 300, 3, A, B);
		book.reference("x", NOW, OptionalLong.of(100), PARAMS);
		PriceBook copy = PriceBook.fromJson(book.toJson());
		assertEquals(book.toJson(), copy.toJson());
	}
}
