package com.mejta.mejtacore.api.metrics;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Cheap timers, counters and gauges that addons report and tools such as MejtaPerf read. Obtain it
 * with {@code MejtaCoreAPI.metrics()}.
 *
 * <p>Recording is lock-free and allocation-free, so it is safe on the server thread in hot paths (a
 * timer costs two {@link System#nanoTime()} calls). Values are cumulative since startup; a reader
 * computes rates by comparing two {@link #snapshot() snapshots}. Nothing is recorded or exported unless
 * an addon reports it, and no metric ever carries player data: names are fixed strings chosen by the
 * addon, never player names or addresses.
 *
 * <p>Asking twice for the same addon ID and name returns the same instrument. Asking for a name that is
 * already used by an instrument of another kind throws.
 *
 * @since API version 4
 */
public interface Metrics {
	/**
	 * A duration recorder, for example the time one anti-cheat check takes per packet.
	 *
	 * <pre>{@code
	 * long start = Timer.start();
	 * runCheck();
	 * CHECK_TIMER.stop(start);
	 * }</pre>
	 */
	interface Timer {
		/** The current {@link System#nanoTime()}, to pass to {@link #stop(long)}. */
		static long start() {
			return System.nanoTime();
		}

		/** Records the time since {@code startNanos}. */
		void stop(long startNanos);

		/** Records one duration. Negative values count as zero. */
		void record(long nanos);
	}

	/** A number that only goes up, for example packets processed or notifications dropped. */
	interface Counter {
		void increment();

		/** Adds {@code amount}; negative amounts are ignored. */
		void add(long amount);
	}

	/** A registered gauge. */
	interface Registration {
		/** Removes the gauge. Calling it again does nothing. */
		void remove();
	}

	/**
	 * @param addonId the reporting addon (the metric is shown under it)
	 * @param name lowercase letters, digits, {@code .}, {@code _} and {@code -}; at most 64 characters
	 * @throws IllegalArgumentException if the ID or name is invalid, or the name is taken by another kind
	 */
	Timer timer(String addonId, String name);

	/** @see #timer(String, String) */
	Counter counter(String addonId, String name);

	/**
	 * Registers a value read on demand, for example a queue's current size. {@code value} is called from
	 * the reader's thread (not necessarily the server thread), so it must be thread-safe, fast, and must not
	 * block. A gauge that throws reads as {@code -1}. Registering an existing name replaces the old gauge.
	 *
	 * @see #timer(String, String)
	 */
	Registration gauge(String addonId, String name, LongSupplier value);

	/** All metrics, sorted by addon ID and then name. Safe from any thread. */
	List<MetricSnapshot> snapshot();
}
