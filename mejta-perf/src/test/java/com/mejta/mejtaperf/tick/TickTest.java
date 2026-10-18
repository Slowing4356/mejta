package com.mejta.mejtaperf.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TickTest {
	private static final long S = 1_000_000_000L;
	private static final long MS = 1_000_000L;

	/** Runs {@code count} ticks of {@code workMs} each, one every {@code periodMs}, starting at {@code from}; returns the end time. */
	private static long ticks(TickStats stats, long from, int count, long workMs, long periodMs) {
		long t = from;
		for (int i = 0; i < count; i++) {
			stats.tickStarted(t);
			stats.tickEnded(t + workMs * MS);
			t += periodMs * MS;
		}
		return t;
	}

	@Test
	void healthyServerIsTwentyTps() {
		TickStats stats = new TickStats();
		long end = ticks(stats, 10 * S, 20 * 60, 10, 50);
		TickStats.Window minute = stats.window(end, 60);
		assertEquals(1200, minute.ticks());
		assertEquals(20.0, minute.tps(), 0.05);
		assertEquals(10.0, minute.meanMs(), 1e-9);
		assertEquals(10.0, minute.p95Ms(), 1e-9);
		assertEquals(1200, stats.totalTicks());
		assertEquals(10 * MS, stats.lastTickNanos());
	}

	@Test
	void slowTicksLowerTpsAndShowInPercentiles() {
		TickStats stats = new TickStats();
		long t = ticks(stats, 0, 19, 20, 50);
		t = ticks(stats, t, 1, 250, 250); // one 250 ms spike
		TickStats.Window w = stats.window(t, 60);
		assertEquals(20, w.ticks());
		assertEquals(250.0, w.maxMs(), 1e-9);
		assertEquals(20.0, w.medianMs(), 1e-9);
		assertTrue(w.tps() < 20, "a long tick delays the next ones");
		TickStats overloaded = new TickStats();
		long e = ticks(overloaded, 0, 100, 100, 100);
		assertEquals(10.0, overloaded.window(e, 5).tps(), 0.01);
	}

	@Test
	void windowsOnlyCountRecentTicksAndStartupIsNotPenalised() {
		TickStats stats = new TickStats();
		long end = ticks(stats, 0, 40, 5, 50); // two seconds of ticks
		assertEquals(20.0, stats.window(end, 60).tps(), 0.01, "only 2 s of history: TPS over the covered span");
		assertEquals(20, stats.window(end, 1).ticks());
		assertTrue(Double.isNaN(new TickStats().window(end, 5).tps()));
		assertEquals(0, stats.window(end + 120 * S, 60).ticks(), "nothing in the last minute");
	}

	@Test
	void bufferWrapsAfterFifteenMinutes() {
		TickStats stats = new TickStats();
		long end = ticks(stats, 0, TickStats.CAPACITY + 500, 1, 50);
		assertEquals(TickStats.CAPACITY, stats.window(end, 3600).ticks());
		assertEquals(TickStats.CAPACITY + 500, stats.totalTicks());
		stats.reset();
		assertEquals(0, stats.window(end, 3600).ticks());
		assertFalse(stats.inTick());
	}

	@Test
	void unmatchedEndIsIgnored() {
		TickStats stats = new TickStats();
		stats.tickEnded(5);
		assertEquals(0, stats.totalTicks());
		stats.tickStarted(10);
		assertTrue(stats.inTick());
	}

	// ---------------------------------------------------------------- LagAlarm

	private static LagAlarm.Event feedSeconds(LagAlarm alarm, long[] clock, int seconds, double mspt) {
		LagAlarm.Event last = LagAlarm.Event.NONE;
		for (int i = 0; i < seconds; i++) {
			clock[0] += S;
			LagAlarm.Event e = alarm.feed(clock[0], clock[0] - S, mspt);
			if (e != LagAlarm.Event.NONE) {
				last = e;
			}
		}
		return last;
	}

	@Test
	void alertOnlyAfterSustainedLagThenRecovery() {
		LagAlarm alarm = new LagAlarm(50, 10 * S, 600 * S);
		long[] clock = {1000 * S};
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 9, 80), "9 s is not enough");
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 1, 30), "a good second resets");
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 9, 80));
		assertEquals(LagAlarm.Event.LAG, feedSeconds(alarm, clock, 1, 95));
		assertTrue(alarm.lagging());
		assertEquals(95, alarm.peakMs(), 1e-9);
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 30, 45), "45 ms is below the threshold but not 80% of it");
		assertEquals(LagAlarm.Event.RECOVERED, feedSeconds(alarm, clock, 10, 20));
		assertFalse(alarm.lagging());
	}

	@Test
	void cooldownSuppressesRepeatedAlerts() {
		LagAlarm alarm = new LagAlarm(50, 5 * S, 600 * S);
		long[] clock = {0};
		assertEquals(LagAlarm.Event.LAG, feedSeconds(alarm, clock, 5, 60));
		assertEquals(LagAlarm.Event.RECOVERED, feedSeconds(alarm, clock, 5, 10));
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 60, 60), "inside the 10 minute cooldown");
		assertEquals(LagAlarm.Event.NONE, feedSeconds(alarm, clock, 5, 10));
		clock[0] += 600 * S;
		assertEquals(LagAlarm.Event.LAG, feedSeconds(alarm, clock, 5, 60));
	}

	@Test
	void oneFreezeLongerThanTheSustainTimeAlerts() {
		LagAlarm alarm = new LagAlarm(50, 10 * S, 600 * S);
		long now = 100 * S;
		// A single 12-second tick: the monitor feeds it with the tick's own start.
		assertEquals(LagAlarm.Event.LAG, alarm.feed(now, now - 12 * S, 12_000));
		assertEquals(LagAlarm.Event.NONE, alarm.feed(now + S, now, Double.NaN), "unknown is ignored");
	}

	// ---------------------------------------------------------------- LevelTimes

	@Test
	void levelTimesAverageOverTheLastMinute() {
		LevelTimes levels = new LevelTimes();
		long t = 0;
		for (int second = 0; second < 120; second++) {
			for (int tick = 0; tick < 20; tick++) {
				levels.started("minecraft:overworld", t);
				levels.ended("minecraft:overworld", t + (second < 60 ? 20 : 5) * MS);
				levels.started("minecraft:the_nether", t);
				levels.ended("minecraft:the_nether", t + MS);
				t += 50 * MS;
			}
			levels.secondPassed();
		}
		Map<String, Double> ms = levels.msPerTick();
		assertEquals(5.0, ms.get("minecraft:overworld"), 1e-9, "the slow first minute has left the window");
		assertEquals(1.0, ms.get("minecraft:the_nether"), 1e-9);
		levels.ended("minecraft:the_end", 5);
		assertFalse(levels.msPerTick().containsKey("minecraft:the_end"));
	}
}
