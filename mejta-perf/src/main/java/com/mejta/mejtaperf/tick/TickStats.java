package com.mejta.mejtaperf.tick;

import java.util.Arrays;

/**
 * The last 15 minutes of server ticks: when each started and how long its work took. MSPT (milliseconds
 * per tick) is the work time, as in vanilla's debug screen; TPS counts ticks that started in a window.
 *
 * <p>Fed and read on the server thread only, so it needs no locking. Memory is fixed (two arrays of
 * {@link #CAPACITY} longs).
 */
public final class TickStats {
	/** 15 minutes at 20 ticks per second. Faster tick rates simply cover a shorter time. */
	public static final int CAPACITY = 20 * 60 * 15;

	private final long[] starts = new long[CAPACITY];
	private final long[] durations = new long[CAPACITY];
	private int head;
	private int size;
	private long tickStart = -1;
	private long total;

	/** Summary of the ticks that started within a window. {@code tps} is NaN with fewer than two ticks. */
	public record Window(int seconds, int ticks, double tps, double meanMs, double medianMs, double p95Ms, double maxMs) {
		public static final Window EMPTY = new Window(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
	}

	/**
	 * A tick started. If the previous one never ended (vanilla's {@code tickServer} returns early while a dedicated
	 * server is paused because it is empty, so the end event does not fire), its start is replaced: nothing is
	 * recorded for it rather than a duration that would include the wait between ticks.
	 */
	public void tickStarted(long nanos) {
		tickStart = nanos;
	}

	public void tickEnded(long nanos) {
		if (tickStart < 0) {
			return;
		}
		starts[head] = tickStart;
		durations[head] = Math.max(0, nanos - tickStart);
		head = (head + 1) % CAPACITY;
		size = Math.min(size + 1, CAPACITY);
		total++;
		tickStart = -1;
	}

	/** Forgets every tick, for example when a new server starts. */
	public void reset() {
		head = 0;
		size = 0;
		total = 0;
		tickStart = -1;
	}

	/** Whether a tick is running (between start and end). */
	public boolean inTick() {
		return tickStart >= 0;
	}

	/** Ticks recorded since startup. */
	public long totalTicks() {
		return total;
	}

	/** Duration of the most recent tick in nanoseconds, or -1 before the first one. */
	public long lastTickNanos() {
		return size == 0 ? -1 : durations[index(0)];
	}

	/** Start of the most recent tick ({@link System#nanoTime()} scale), or -1 before the first one. */
	public long lastTickStart() {
		return size == 0 ? -1 : starts[index(0)];
	}

	/** Ticks that started in the last {@code seconds} seconds before {@code now}. */
	public Window window(long now, int seconds) {
		long from = now - seconds * 1_000_000_000L;
		int n = 0;
		while (n < size && starts[index(n)] >= from) {
			n++;
		}
		if (n == 0) {
			return Window.EMPTY;
		}
		long[] values = new long[n];
		double sum = 0;
		for (int i = 0; i < n; i++) {
			values[i] = durations[index(i)];
			sum += values[i];
		}
		Arrays.sort(values);
		// The oldest tick in the window may be the very first one; use the covered span, not the full window.
		long span = n == size ? now - starts[index(n - 1)] : now - from;
		double tps = n < 2 || span <= 0 ? Double.NaN : n / (span / 1e9);
		return new Window(seconds, n, tps, sum / n / 1e6, values[n / 2] / 1e6, values[Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1)] / 1e6,
				values[n - 1] / 1e6);
	}

	/**
	 * Like {@link #window}, but when no tick started in it because the last one took longer (a freeze), the window
	 * is widened to begin at that tick's start, so it holds at least that tick. Empty only before the first tick.
	 */
	public Window windowOrLastTick(long now, int seconds) {
		Window w = window(now, seconds);
		if (w.ticks() > 0 || size == 0) {
			return w;
		}
		long since = Math.max(0, now - lastTickStart());
		return window(now, (int) Math.min(Integer.MAX_VALUE, since / 1_000_000_000L + 1));
	}

	/** Position of the {@code i}-th newest tick. */
	private int index(int i) {
		return (head - 1 - i + 2 * CAPACITY) % CAPACITY;
	}
}
