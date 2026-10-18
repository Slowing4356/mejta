package com.mejta.mejtacore.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.metrics.MetricSnapshot;
import com.mejta.mejtacore.api.metrics.MetricSnapshot.Kind;
import com.mejta.mejtacore.api.metrics.Metrics;

class MetricRegistryTest {
	private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
	private final MetricRegistry metrics = new MetricRegistry(now::get);

	private MetricSnapshot find(String id) {
		return metrics.snapshot().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void timerAccumulatesAndKeepsAPeak() {
		Metrics.Timer timer = metrics.timer("mejtaac", "check.reach");
		assertSame(timer, metrics.timer("mejtaac", "check.reach"), "same instrument for the same name");
		long start = now.get();
		now.addAndGet(5_000);
		timer.stop(start);
		timer.record(2_000);
		timer.record(-7);
		MetricSnapshot s = find("mejtaac:check.reach");
		assertEquals(Kind.TIMER, s.kind());
		assertEquals(3, s.count());
		assertEquals(7_000, s.totalNanos());
		assertEquals(5_000, s.maxNanos());
	}

	@Test
	void peakFadesAfterTwoWindows() {
		Metrics.Timer timer = metrics.timer("mejtaperf", "t");
		timer.record(9_000);
		now.addAndGet(MetricRegistry.WINDOW_NANOS);
		timer.record(1_000);
		assertEquals(9_000, find("mejtaperf:t").maxNanos(), "previous window still counts");
		now.addAndGet(MetricRegistry.WINDOW_NANOS);
		assertEquals(1_000, find("mejtaperf:t").maxNanos());
		now.addAndGet(5 * MetricRegistry.WINDOW_NANOS);
		assertEquals(0, find("mejtaperf:t").maxNanos(), "nothing recorded recently");
		assertEquals(2, find("mejtaperf:t").count(), "totals are never reset");
	}

	@Test
	void countersAndGauges() {
		Metrics.Counter counter = metrics.counter("mejtacore", "dropped");
		counter.increment();
		counter.add(4);
		counter.add(-10);
		assertEquals(5, find("mejtacore:dropped").count());

		AtomicLong depth = new AtomicLong(3);
		Metrics.Registration reg = metrics.gauge("mejtacore", "queue", depth::get);
		assertEquals(3, find("mejtacore:queue").count());
		depth.set(8);
		assertEquals(8, find("mejtacore:queue").count());
		metrics.gauge("mejtacore", "broken", () -> {
			throw new IllegalStateException();
		});
		assertEquals(-1, find("mejtacore:broken").count());
		reg.remove();
		reg.remove();
		assertTrue(metrics.snapshot().stream().noneMatch(s -> s.id().equals("mejtacore:queue")));
	}

	@Test
	void namesAreValidatedAndKindsCannotClash() {
		metrics.timer("mejtaac", "x");
		assertThrows(IllegalArgumentException.class, () -> metrics.counter("mejtaac", "x"));
		assertThrows(IllegalArgumentException.class, () -> metrics.gauge("mejtaac", "x", () -> 1));
		assertThrows(IllegalArgumentException.class, () -> metrics.timer("MejtaAC", "y"));
		assertThrows(IllegalArgumentException.class, () -> metrics.timer("mejtaac", "Steve's ping"));
		assertThrows(IllegalArgumentException.class, () -> metrics.timer("mejtaac", "a".repeat(65)));
		metrics.timer("b_addon", "a");
		metrics.counter("a_addon", "z");
		List<MetricSnapshot> all = metrics.snapshot();
		assertEquals("a_addon", all.get(0).addonId(), "sorted by addon");
		assertEquals("b_addon", all.get(1).addonId());
	}

	@Test
	void concurrentRecordingLosesNothing() throws InterruptedException {
		Metrics.Timer timer = metrics.timer("mejtaac", "c");
		Metrics.Counter counter = metrics.counter("mejtaac", "n");
		ExecutorService pool = Executors.newFixedThreadPool(8);
		for (int t = 0; t < 8; t++) {
			pool.execute(() -> {
				for (int i = 0; i < 10_000; i++) {
					timer.record(10);
					counter.increment();
				}
			});
		}
		pool.shutdown();
		assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
		assertEquals(80_000, find("mejtaac:c").count());
		assertEquals(800_000, find("mejtaac:c").totalNanos());
		assertEquals(80_000, find("mejtaac:n").count());
	}
}
