package com.mejta.mejtaqol.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaqol.afk.AfkClock.Change;

class AfkClockTest {
	static final UUID P = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
	static final long MIN = 60_000;

	@Test
	void idlePlayerBecomesAfkOnceAndReturnsOnActivity() {
		AfkClock clock = new AfkClock();
		clock.join(P, 0);
		assertEquals(Change.NONE, clock.check(P, 5 * MIN - 1, 5, 0, false));
		assertEquals(Change.BECAME_AFK, clock.check(P, 5 * MIN, 5, 0, false));
		assertEquals(Change.NONE, clock.check(P, 6 * MIN, 5, 0, false), "only announced once");
		assertTrue(clock.isAfk(P));
		assertEquals(Change.RETURNED, clock.active(P, 7 * MIN));
		assertFalse(clock.isAfk(P));
		assertEquals(Change.NONE, clock.active(P, 7 * MIN + 1));
	}

	@Test
	void autoAfkCanBeOff() {
		AfkClock clock = new AfkClock();
		clock.join(P, 0);
		assertEquals(Change.NONE, clock.check(P, 1000 * MIN, 0, 0, false));
	}

	@Test
	void toggleSetsAndClears() {
		AfkClock clock = new AfkClock();
		clock.join(P, 0);
		assertEquals(Change.BECAME_AFK, clock.toggle(P, 1));
		assertEquals(Change.NONE, clock.check(P, 10 * MIN, 5, 0, false), "already AFK");
		assertEquals(Change.RETURNED, clock.toggle(P, 2));
		assertEquals(0, clock.idleMillis(P, 2));
	}

	@Test
	void kickAfterLimitUnlessExempt() {
		AfkClock clock = new AfkClock();
		clock.join(P, 0);
		assertEquals(Change.KICK, clock.check(P, 30 * MIN, 5, 30, false));
		assertEquals(Change.BECAME_AFK, clock.check(P, 30 * MIN, 5, 30, true));
		assertEquals(Change.NONE, clock.check(P, 100 * MIN, 5, 30, true));
	}

	@Test
	void leaveForgetsThePlayer() {
		AfkClock clock = new AfkClock();
		clock.join(P, 0);
		clock.toggle(P, 1);
		clock.leave(P);
		assertFalse(clock.isAfk(P));
		assertEquals(0, clock.idleMillis(P, 100 * MIN));
	}
}
