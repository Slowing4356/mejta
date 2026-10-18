package com.mejta.mejtacore.api.metrics;

/**
 * One metric's values at the moment of a {@link Metrics#snapshot()}.
 *
 * @param addonId  the addon that reports it
 * @param name     the metric's name
 * @param kind     which kind of instrument it is
 * @param count    timers: durations recorded; counters: the value; gauges: the current value
 * @param totalNanos timers: sum of all recorded durations; 0 otherwise
 * @param maxNanos   timers: the longest single duration recorded in roughly the last minute (between 30
 *                   and 60 seconds); 0 otherwise
 * @since API version 4
 */
public record MetricSnapshot(String addonId, String name, Kind kind, long count, long totalNanos, long maxNanos) {
	public enum Kind {
		TIMER, COUNTER, GAUGE
	}

	/** {@code addonId:name}. */
	public String id() {
		return addonId + ":" + name;
	}
}
