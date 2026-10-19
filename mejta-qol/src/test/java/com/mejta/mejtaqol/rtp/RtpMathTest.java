package com.mejta.mejtaqol.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class RtpMathTest {
	@Test
	void parsesCentre() {
		assertEquals(Optional.of(new RtpMath.Center.Spawn()), RtpMath.parseCenter(" Spawn "));
		assertEquals(Optional.of(new RtpMath.Center.Fixed(1500, -200)), RtpMath.parseCenter("1500, -200"));
		assertTrue(RtpMath.parseCenter("1,2,3").isEmpty());
		assertTrue(RtpMath.parseCenter("a,b").isEmpty());
		assertTrue(RtpMath.parseCenter("40000000,0").isEmpty());
		assertTrue(RtpMath.parseCenter(null).isEmpty());
	}

	@Test
	void columnsStayInTheRing() {
		SplittableRandom random = new SplittableRandom(42);
		for (int i = 0; i < 10_000; i++) {
			RtpMath.Column c = RtpMath.randomColumn(100, -100, 500, 2000, random);
			double d = Math.hypot(c.x() - 100, c.z() + 100);
			assertTrue(d >= 499 && d <= 2001, "distance " + d);
		}
	}

	@Test
	void swappedRadiiAndWorldEdge() {
		SplittableRandom random = new SplittableRandom(7);
		RtpMath.Column c = RtpMath.randomColumn(0, 0, 300, 100, random);
		double d = Math.hypot(c.x(), c.z());
		assertTrue(d >= 99 && d <= 301, "distance " + d);
		RtpMath.Column edge = RtpMath.randomColumn(29_999_000, 29_999_000, 5000, 5000, random);
		assertTrue(edge.x() <= 29_999_984 && edge.z() <= 29_999_984);
	}

	@Test
	void ringIsUniformByArea() {
		// Half of the ring's area lies beyond sqrt((min^2 + max^2) / 2).
		SplittableRandom random = new SplittableRandom(1);
		double split = Math.sqrt((1000.0 * 1000 + 3000.0 * 3000) / 2);
		int outer = 0;
		int n = 20_000;
		for (int i = 0; i < n; i++) {
			RtpMath.Column c = RtpMath.randomColumn(0, 0, 1000, 3000, random);
			if (Math.hypot(c.x(), c.z()) > split) {
				outer++;
			}
		}
		assertTrue(Math.abs(outer - n / 2) < n / 50, "outer half: " + outer);
	}
}
