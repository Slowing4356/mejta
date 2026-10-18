package com.mejta.mejtaperf.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.metrics.MetricSnapshot;
import com.mejta.mejtacore.api.metrics.MetricSnapshot.Kind;

class MetricRatesTest {
	private static final long S = 1_000_000_000L;

	@Test
	void ratesOverTheWindow() {
		MetricRates rates = new MetricRates();
		rates.add(0, 0, List.of(
				new MetricSnapshot("mejtaac", "hook.move", Kind.TIMER, 1000, 10_000_000, 0),
				new MetricSnapshot("mejtacore", "notify.queue.ingame", Kind.GAUGE, 3, 0, 0),
				new MetricSnapshot("mejtacore", "dropped", Kind.COUNTER, 10, 0, 0)));
		rates.add(10 * S, 200, List.of(
				new MetricSnapshot("mejtaac", "hook.move", Kind.TIMER, 3000, 30_000_000, 2_000_000),
				new MetricSnapshot("mejtaac", "hook.attack", Kind.TIMER, 10, 100_000_000, 50_000_000),
				new MetricSnapshot("mejtacore", "notify.queue.ingame", Kind.GAUGE, 7, 0, 0),
				new MetricSnapshot("mejtacore", "dropped", Kind.COUNTER, 30, 0, 0)));
		assertEquals(10.0, rates.windowSeconds(), 1e-9);
		List<MetricRates.Rate> list = rates.rates();
		assertEquals(4, list.size());
		MetricRates.Rate attack = list.get(0);
		assertEquals("mejtaac:hook.attack", attack.id(), "most expensive timer first");
		assertEquals(0.5, attack.msPerTick(), 1e-9);
		MetricRates.Rate move = list.get(1);
		assertEquals(200.0, move.perSecond(), 1e-9);
		assertEquals(10.0, move.meanMicros(), 1e-9);
		assertEquals(0.1, move.msPerTick(), 1e-9);
		assertEquals(2.0, move.peakMs(), 1e-9);
		assertEquals(Kind.COUNTER, list.get(2).kind());
		assertEquals(2.0, list.get(2).perSecond(), 1e-9);
		assertEquals(7, list.get(3).value());
		assertTrue(Double.isNaN(list.get(3).perSecond()));
	}

	@Test
	void keepsAboutOneMinute() {
		MetricRates rates = new MetricRates();
		for (int i = 0; i <= 120; i++) {
			rates.add(i * S, i * 20L, List.of(new MetricSnapshot("a_b", "t", Kind.TIMER, i * 10L, i * 1_000_000L, 0)));
		}
		assertEquals(60.0, rates.windowSeconds(), 1e-9);
		assertEquals(10.0, rates.rates().get(0).perSecond(), 1e-9);
		rates.reset();
		assertTrue(rates.rates().isEmpty());
	}
}
