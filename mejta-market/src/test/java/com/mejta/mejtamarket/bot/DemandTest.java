package com.mejta.mejtamarket.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DemandTest {
	private static final UUID A = new UUID(0, 1);
	private static final UUID B = new UUID(0, 2);

	@Test
	void searchesCountOncePerPlayerAndFade() {
		Demand d = new Demand();
		assertTrue(d.searched(A, "x", 0));
		assertFalse(d.searched(A, "x", 1000), "the same player again right away");
		assertTrue(d.searched(B, "x", 1000));
		assertEquals(2.0, d.of("x", 1000), 0.01);
		assertEquals(1.0, d.of("x", 1000 + Demand.HALF_LIFE), 0.01);
		assertEquals("x", d.top(1000).orElseThrow());
		assertTrue(d.weight("x", 1000) > d.weight("y", 1000));
		d.prune(1000 + 20 * Demand.HALF_LIFE);
		assertTrue(d.top(1000 + 20 * Demand.HALF_LIFE).isEmpty());
	}

	@Test
	void survivesJson() {
		Demand d = new Demand();
		d.searched(A, "x", 5);
		assertEquals(d.toJson(), Demand.fromJson(d.toJson()).toJson());
	}
}
