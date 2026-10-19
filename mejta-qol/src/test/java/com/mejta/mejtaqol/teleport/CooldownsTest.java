package com.mejta.mejtaqol.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CooldownsTest {
	static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

	@Test
	void countsDownPerPlayerAndFeature() {
		Cooldowns c = new Cooldowns();
		assertEquals(0, c.remaining(A, "home", 10, 1_000));
		c.use(A, "home", 1_000);
		assertEquals(10_000, c.remaining(A, "home", 10, 1_000));
		assertEquals(4_000, c.remaining(A, "home", 10, 7_000));
		assertEquals(0, c.remaining(A, "home", 10, 11_000));
		assertEquals(0, c.remaining(A, "warp", 10, 2_000), "other feature");
		assertEquals(0, c.remaining(B, "home", 10, 2_000), "other player");
	}

	@Test
	void zeroSecondsMeansNoCooldownAndChangesApplyAtOnce() {
		Cooldowns c = new Cooldowns();
		c.use(A, "rtp", 0);
		assertEquals(0, c.remaining(A, "rtp", 0, 1));
		assertEquals(299_999, c.remaining(A, "rtp", 300, 1));
		assertEquals(59_999, c.remaining(A, "rtp", 60, 1), "a lowered setting shortens a running cooldown");
	}

	@Test
	void clearsOnePlayerOrAll() {
		Cooldowns c = new Cooldowns();
		c.use(A, "home", 0);
		c.use(B, "home", 0);
		c.clear(A);
		assertEquals(0, c.remaining(A, "home", 10, 1));
		assertEquals(9_999, c.remaining(B, "home", 10, 1));
		c.clearAll();
		assertEquals(0, c.remaining(B, "home", 10, 1));
	}
}
