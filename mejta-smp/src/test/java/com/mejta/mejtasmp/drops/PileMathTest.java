package com.mejta.mejtasmp.drops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PileMathTest {
	@Test
	void splitKeepsEveryItem() {
		for (int total : new int[] {0, 1, 63, 64, 65, 1000, 50_000}) {
			for (int max : new int[] {1, 16, 64}) {
				PileMath.Split split = PileMath.split(total, max);
				assertEquals(total, split.visible() + split.extra(), total + "/" + max);
				assertTrue(split.visible() <= max);
				assertTrue(split.extra() == 0 || split.visible() == max, "the visible stack is full while more is counted");
			}
		}
	}

	@Test
	void fitsRespectsTheCapWithoutOverflow() {
		assertTrue(PileMath.fits(100, 100, 200));
		assertFalse(PileMath.fits(100, 101, 200));
		assertFalse(PileMath.fits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), "no int overflow");
		assertFalse(PileMath.fits(0, 5, 100), "an empty pile never merges");
		assertEquals("1,000", PileMath.format(1000));
	}
}
