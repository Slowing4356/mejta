package com.mejta.mejtaperf.tick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tick time of each dimension (level), as milliseconds per tick averaged over the last minute. A level's
 * tick covers its entities, block entities, chunks and weather; the rest of a server tick is players'
 * connections, the player list and scheduled tasks.
 *
 * <p>Server thread only. Dimensions are keyed by their ID, for example {@code minecraft:overworld}.
 */
public final class LevelTimes {
	private static final int SECONDS = 60;

	private static final class Totals {
		long nanos;
		long ticks;
		long started = -1;
	}

	private final Map<String, Totals> totals = new LinkedHashMap<>();
	private final Deque<Map<String, long[]>> history = new ArrayDeque<>();

	public void started(String level, long now) {
		totals.computeIfAbsent(level, k -> new Totals()).started = now;
	}

	public void ended(String level, long now) {
		Totals t = totals.get(level);
		if (t == null || t.started < 0) {
			return;
		}
		t.nanos += Math.max(0, now - t.started);
		t.ticks++;
		t.started = -1;
	}

	public void reset() {
		totals.clear();
		history.clear();
	}

	/** Call once per second: remembers the totals so the last minute can be computed. */
	public void secondPassed() {
		Map<String, long[]> copy = new HashMap<>();
		totals.forEach((level, t) -> copy.put(level, new long[] {t.nanos, t.ticks}));
		history.addLast(copy);
		while (history.size() > SECONDS + 1) {
			history.removeFirst();
		}
	}

	/** Milliseconds per tick of each level over the last minute (or since startup), in first-seen order. */
	public Map<String, Double> msPerTick() {
		Map<String, Double> out = new LinkedHashMap<>();
		Map<String, long[]> oldest = history.isEmpty() ? Map.of() : history.peekFirst();
		totals.forEach((level, t) -> {
			long[] then = oldest.getOrDefault(level, new long[2]);
			long ticks = t.ticks - then[1];
			out.put(level, ticks <= 0 ? 0.0 : (t.nanos - then[0]) / (double) ticks / 1e6);
		});
		return out;
	}
}
