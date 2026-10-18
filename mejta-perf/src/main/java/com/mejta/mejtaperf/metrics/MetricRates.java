package com.mejta.mejtaperf.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mejta.mejtacore.api.metrics.MetricSnapshot;
import com.mejta.mejtacore.api.metrics.MetricSnapshot.Kind;

/**
 * Turns MejtaCore's cumulative metrics into rates over the last minute: calls per second, average
 * duration, and milliseconds per server tick. Fed once per second with a snapshot and the server's tick
 * count. Server thread only.
 */
public final class MetricRates {
	private static final int SECONDS = 60;

	private record Point(long nanos, long ticks, Map<String, MetricSnapshot> metrics) {
	}

	/**
	 * One metric over the window.
	 *
	 * @param perSecond timers: calls per second; counters: increase per second; gauges: NaN
	 * @param meanMicros timers: average duration in microseconds; NaN otherwise
	 * @param msPerTick timers: time spent per server tick, in milliseconds; NaN otherwise. Includes work done on
	 *        other threads, so it is a cost, not necessarily a share of MSPT
	 * @param peakMs timers: longest single duration in about the last minute; NaN otherwise
	 * @param value gauges: the current value; timers and counters: the total since startup
	 */
	public record Rate(String addonId, String name, Kind kind, double perSecond, double meanMicros, double msPerTick, double peakMs,
			long value) {
		public String id() {
			return addonId + ":" + name;
		}
	}

	private final Deque<Point> points = new ArrayDeque<>();

	public void add(long nanos, long ticks, List<MetricSnapshot> snapshot) {
		Map<String, MetricSnapshot> byId = new HashMap<>(snapshot.size() * 2);
		for (MetricSnapshot s : snapshot) {
			byId.put(s.id(), s);
		}
		points.addLast(new Point(nanos, ticks, byId));
		while (points.size() > SECONDS + 1) {
			points.removeFirst();
		}
	}

	public void reset() {
		points.clear();
	}

	/** Seconds covered by {@link #rates()}; 0 until two snapshots were added. */
	public double windowSeconds() {
		return points.size() < 2 ? 0 : (points.peekLast().nanos - points.peekFirst().nanos) / 1e9;
	}

	/** Every metric in the newest snapshot, timers with the highest cost per tick first, then counters, then gauges. */
	public List<Rate> rates() {
		List<Rate> out = new ArrayList<>();
		if (points.isEmpty()) {
			return out;
		}
		Point now = points.peekLast();
		Point then = points.peekFirst();
		double seconds = (now.nanos - then.nanos) / 1e9;
		long ticks = now.ticks - then.ticks;
		for (MetricSnapshot s : now.metrics.values()) {
			MetricSnapshot old = then.metrics.get(s.id());
			out.add(switch (s.kind()) {
				case TIMER -> {
					long calls = old == null ? s.count() : s.count() - old.count();
					long nanos = old == null ? s.totalNanos() : s.totalNanos() - old.totalNanos();
					yield new Rate(s.addonId(), s.name(), s.kind(), seconds > 0 ? calls / seconds : Double.NaN,
							calls > 0 ? nanos / (double) calls / 1e3 : Double.NaN, ticks > 0 ? nanos / (double) ticks / 1e6 : Double.NaN,
							s.maxNanos() / 1e6, s.count());
				}
				case COUNTER -> {
					long delta = old == null ? s.count() : s.count() - old.count();
					yield new Rate(s.addonId(), s.name(), s.kind(), seconds > 0 ? delta / seconds : Double.NaN, Double.NaN, Double.NaN,
							Double.NaN, s.count());
				}
				case GAUGE -> new Rate(s.addonId(), s.name(), s.kind(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, s.count());
			});
		}
		out.sort(Comparator.comparing(Rate::kind).thenComparing(r -> Double.isNaN(r.msPerTick()) ? 0 : -r.msPerTick())
				.thenComparing(Rate::id));
		return out;
	}
}
