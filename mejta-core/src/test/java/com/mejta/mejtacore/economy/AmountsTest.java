package com.mejta.mejtacore.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class AmountsTest {
	@Test
	void parsesDecimalsAndSuffixes() {
		assertEquals(OptionalLong.of(1250), Amounts.parse("12.5", 2));
		assertEquals(OptionalLong.of(100_000_00), Amounts.parse("100,000", 2));
		assertEquals(OptionalLong.of(2_500_00), Amounts.parse("2.5k", 2));
		assertEquals(OptionalLong.of(1_000_000), Amounts.parse("1M", 0));
		assertEquals(OptionalLong.of(5), Amounts.parse(".05", 2));
	}

	@Test
	void refusesBadAmounts() {
		for (String bad : new String[] { "", "0", "-5", "1.234", "abc", ".", "1e5", "99999999999999999999", "NaN", "1.5.5" }) {
			assertTrue(Amounts.parse(bad, 2).isEmpty(), bad);
		}
		assertTrue(Amounts.parse("1.5", 0).isEmpty());
		assertTrue(Amounts.parse(null, 2).isEmpty());
	}

	@Test
	void printsWithGrouping() {
		assertEquals("1,234.56", Amounts.plain(123456, 2));
		assertEquals("5.00", Amounts.plain(500, 2));
		assertEquals("-0.07", Amounts.plain(-7, 2));
		assertEquals("42", Amounts.plain(42, 0));
	}
}
