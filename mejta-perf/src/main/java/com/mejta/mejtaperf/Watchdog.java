package com.mejta.mejtaperf;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;

import com.mejta.mejtaperf.tick.StallDetector;

/**
 * Once a second, on its own daemon thread ({@code MejtaPerf-Watchdog}), checks whether the server thread has been
 * inside one tick for longer than the sustain time, and reports each such stall once. It reads only the
 * monitor's volatile tick state, never the server thread's own data.
 */
final class Watchdog {
	private final BooleanSupplier inTick;
	private final LongSupplier tickStart;
	private final LongSupplier sustainNanos;
	private final LongConsumer stalled;
	private final Logger log;
	private ScheduledExecutorService executor;

	/** @param stalled called on the watchdog thread with how long the tick has run, in nanoseconds */
	Watchdog(BooleanSupplier inTick, LongSupplier tickStart, LongSupplier sustainNanos, LongConsumer stalled, Logger log) {
		this.inTick = inTick;
		this.tickStart = tickStart;
		this.sustainNanos = sustainNanos;
		this.stalled = stalled;
		this.log = log;
	}

	/** Starts checking, with no stall reported yet. Called from the server lifecycle (one thread). */
	synchronized void start() {
		stop();
		StallDetector detector = new StallDetector();
		executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "MejtaPerf-Watchdog");
			t.setDaemon(true);
			return t;
		});
		executor.scheduleWithFixedDelay(() -> check(detector), 1, 1, TimeUnit.SECONDS);
	}

	/** Stops the thread; a check in progress is interrupted. */
	synchronized void stop() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private void check(StallDetector detector) {
		try {
			// inTick first: the start it then reads is that tick's or a newer one, never an older one.
			boolean tick = inTick.getAsBoolean();
			long stuck = detector.check(tick, tickStart.getAsLong(), System.nanoTime(), sustainNanos.getAsLong());
			if (stuck >= 0) {
				stalled.accept(stuck);
			}
		} catch (RuntimeException e) {
			log.warn("MejtaPerf watchdog check failed", e); // an exception would end the schedule
		}
	}
}
