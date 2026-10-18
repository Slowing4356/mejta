package com.mejta.mejtacore.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.mejta.mejtacore.api.metrics.MetricSnapshot;
import com.mejta.mejtacore.api.metrics.MetricSnapshot.Kind;
import com.mejta.mejtacore.api.metrics.Metrics;

/** The {@link Metrics} implementation: lock-free instruments in one concurrent map. */
public final class MetricRegistry implements Metrics {
	private static final MetricRegistry INSTANCE = new MetricRegistry(System::nanoTime);
	private static final Pattern ADDON_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	/** Length of one peak window; {@code maxNanos} covers the current and the previous window. */
	static final long WINDOW_NANOS = 30_000_000_000L;

	private final Map<String, Instrument> instruments = new ConcurrentHashMap<>();
	private final LongSupplier clock;

	private sealed interface Instrument permits TimerImpl, CounterImpl, GaugeImpl {
		String addonId();

		String name();

		MetricSnapshot read();
	}

	MetricRegistry(LongSupplier clock) {
		this.clock = clock;
	}

	public static MetricRegistry get() {
		return INSTANCE;
	}

	@Override
	public Timer timer(String addonId, String name) {
		return (Timer) instrument(addonId, name, Kind.TIMER);
	}

	@Override
	public Counter counter(String addonId, String name) {
		return (Counter) instrument(addonId, name, Kind.COUNTER);
	}

	@Override
	public Registration gauge(String addonId, String name, LongSupplier value) {
		Objects.requireNonNull(value, "value");
		String key = key(addonId, name);
		GaugeImpl gauge = new GaugeImpl(addonId, name, value);
		instruments.compute(key, (k, old) -> {
			if (old != null && !(old instanceof GaugeImpl)) {
				throw new IllegalArgumentException("Metric " + k + " is already a " + kindOf(old));
			}
			return gauge;
		});
		return () -> instruments.remove(key, gauge);
	}

	private Instrument instrument(String addonId, String name, Kind kind) {
		String key = key(addonId, name);
		Instrument existing = instruments.computeIfAbsent(key, k -> switch (kind) {
			case TIMER -> new TimerImpl(addonId, name, clock);
			case COUNTER -> new CounterImpl(addonId, name);
			case GAUGE -> throw new IllegalStateException();
		});
		if (kindOf(existing) != kind) {
			throw new IllegalArgumentException("Metric " + key + " is already a " + kindOf(existing));
		}
		return existing;
	}

	private static Kind kindOf(Instrument instrument) {
		return switch (instrument) {
			case TimerImpl t -> Kind.TIMER;
			case CounterImpl c -> Kind.COUNTER;
			case GaugeImpl g -> Kind.GAUGE;
		};
	}

	private static String key(String addonId, String name) {
		if (addonId == null || !ADDON_ID.matcher(addonId).matches()) {
			throw new IllegalArgumentException("Invalid addon ID for a metric: " + addonId);
		}
		if (name == null || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid metric name: " + name);
		}
		return addonId + ":" + name;
	}

	@Override
	public List<MetricSnapshot> snapshot() {
		List<MetricSnapshot> out = new ArrayList<>(instruments.size());
		for (Instrument instrument : instruments.values()) {
			out.add(instrument.read());
		}
		out.sort(Comparator.comparing(MetricSnapshot::addonId).thenComparing(MetricSnapshot::name));
		return out;
	}

	// ---------------------------------------------------------------- instruments

	private static final class TimerImpl implements Instrument, Timer {
		private final String addonId;
		private final String name;
		private final LongSupplier clock;
		private final LongAdder count = new LongAdder();
		private final LongAdder total = new LongAdder();
		// Peak of the current window and of the previous one, so a reader always sees at least 30 s.
		private final AtomicLong window = new AtomicLong(Long.MIN_VALUE);
		private final AtomicLong peak = new AtomicLong();
		private volatile long previousPeak;

		TimerImpl(String addonId, String name, LongSupplier clock) {
			this.addonId = addonId;
			this.name = name;
			this.clock = clock;
		}

		@Override
		public String addonId() {
			return addonId;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public void stop(long startNanos) {
			long now = clock.getAsLong();
			add(now - startNanos, now);
		}

		@Override
		public void record(long nanos) {
			add(nanos, clock.getAsLong());
		}

		private void add(long nanos, long now) {
			long value = Math.max(0, nanos);
			count.increment();
			total.add(value);
			roll(now);
			long current;
			while (value > (current = peak.get()) && !peak.compareAndSet(current, value)) {
				// retry
			}
		}

		private void roll(long now) {
			long w = Math.floorDiv(now, WINDOW_NANOS);
			long seen = window.get();
			if (w != seen && window.compareAndSet(seen, w)) {
				// Approximate under contention: a value recorded during the swap may land in either window.
				previousPeak = w == seen + 1 ? peak.getAndSet(0) : 0;
				if (w != seen + 1) {
					peak.set(0);
				}
			}
		}

		@Override
		public MetricSnapshot read() {
			roll(clock.getAsLong());
			return new MetricSnapshot(addonId, name, Kind.TIMER, count.sum(), total.sum(), Math.max(peak.get(), previousPeak));
		}
	}

	private record CounterImpl(String addonId, String name, LongAdder value) implements Instrument, Counter {
		CounterImpl(String addonId, String name) {
			this(addonId, name, new LongAdder());
		}

		@Override
		public void increment() {
			value.increment();
		}

		@Override
		public void add(long amount) {
			if (amount > 0) {
				value.add(amount);
			}
		}

		@Override
		public MetricSnapshot read() {
			return new MetricSnapshot(addonId, name, Kind.COUNTER, value.sum(), 0, 0);
		}
	}

	private record GaugeImpl(String addonId, String name, LongSupplier value) implements Instrument {
		@Override
		public MetricSnapshot read() {
			long v;
			try {
				v = value.getAsLong();
			} catch (Throwable t) {
				v = -1;
			}
			return new MetricSnapshot(addonId, name, Kind.GAUGE, v, 0, 0);
		}
	}
}
