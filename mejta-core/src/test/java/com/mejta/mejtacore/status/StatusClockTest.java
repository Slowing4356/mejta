package com.mejta.mejtacore.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StatusClockTest {
	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

	@Test
	void idlePlayerBecomesAfkAndActivityEndsIt() {
		StatusClock clock = new StatusClock();
		clock.join(A, 0);
		assertEquals(StatusClock.Change.NONE, clock.check(A, 4 * 60_000L, 5));
		assertEquals(StatusClock.Change.BECAME_AFK, clock.check(A, 5 * 60_000L, 5));
		assertEquals(StatusClock.Change.NONE, clock.check(A, 6 * 60_000L, 5), "only once");
		assertTrue(clock.isAfk(A));
		assertEquals(StatusClock.Change.RETURNED, clock.active(A, 6 * 60_000L));
		assertFalse(clock.isAfk(A));
		assertEquals(0, clock.idleMillis(A, 6 * 60_000L));
	}

	@Test
	void autoAfkOffAndUnknownPlayers() {
		StatusClock clock = new StatusClock();
		clock.join(A, 0);
		assertEquals(StatusClock.Change.NONE, clock.check(A, 999 * 60_000L, 0), "0 = off");
		assertEquals(StatusClock.Change.NONE, clock.check(B, 999 * 60_000L, 5), "offline player");
		assertEquals(StatusClock.Change.NONE, clock.active(B, 0), "activity of an unknown player changes nothing");
		assertFalse(clock.isAfk(B));
	}

	@Test
	void requestSetsAndClears() {
		StatusClock clock = new StatusClock();
		clock.join(A, 0);
		assertEquals(StatusClock.Change.BECAME_AFK, clock.set(A, true, 1000));
		assertEquals(StatusClock.Change.NONE, clock.set(A, true, 2000));
		assertEquals(StatusClock.Change.RETURNED, clock.set(A, false, 3000));
		assertEquals(0, clock.idleMillis(A, 3000), "clearing counts as activity");
		clock.leave(A);
		assertFalse(clock.isAfk(A));
	}

	@Test
	void combatTagsBothForTheConfiguredTime() {
		StatusClock clock = new StatusClock();
		clock.combat(A, B, 10_000);
		assertEquals(10_000, clock.combatRemaining(A, 10_000, 10));
		assertEquals(4_000, clock.combatRemaining(B, 16_000, 10));
		assertEquals(0, clock.combatRemaining(A, 20_000, 10));
		assertEquals(0, clock.combatRemaining(A, 10_000, 0), "0 = off");
		clock.combat(A, B, 30_000);
		clock.endCombat(A);
		assertEquals(0, clock.combatRemaining(A, 30_000, 10), "death ends it");
		assertEquals(10_000, clock.combatRemaining(B, 30_000, 10));
	}
}
