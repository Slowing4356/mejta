package com.mejta.mejtaqol.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationsTest {
	@Test
	void formatsShortAndLongDurations() {
		assertEquals("1s", Durations.format(0));
		assertEquals("1s", Durations.format(200));
		assertEquals("2s", Durations.format(1001));
		assertEquals("59s", Durations.format(59_000));
		assertEquals("1m 00s", Durations.format(60_000));
		assertEquals("2m 05s", Durations.format(125_000));
		assertEquals("1h 02m", Durations.format(3_720_000));
	}
}
