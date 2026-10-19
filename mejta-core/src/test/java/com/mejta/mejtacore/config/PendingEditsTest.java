package com.mejta.mejtacore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * The client's Server Settings with real network latency: catalogs that the server sent before it handled
 * an edit must not undo the edit on screen, or the next click re-sends the old value.
 */
class PendingEditsTest {
	private final AtomicLong now = new AtomicLong(1_000);
	private final PendingEdits pending = new PendingEdits(now::get);

	@Test
	void sentValuesWinUntilTheLastEditIsAnswered() {
		pending.sent("example", Map.of("radius", "9"));
		pending.sent("example", Map.of("radius", "10"));
		// A catalog from before both edits arrives now: the client keeps showing 10.
		assertEquals(Map.of("radius", "10"), pending.overrides("example"));
		assertEquals(Map.of(), pending.overrides("other"));

		pending.answered(); // result of the first edit; the catalog after it still predates the second
		assertEquals(Map.of("radius", "10"), pending.overrides("example"));
		assertTrue(pending.isPending());

		pending.answered(); // result of the last edit: the next catalog is the server's truth
		assertFalse(pending.isPending());
		assertEquals(Map.of(), pending.overrides("example"));
	}

	@Test
	void quickClicksAreNotLost() {
		// Simulates the menu: each click computes from what is shown, the server applies edits in order.
		int server = 8;
		int shown = 8;
		for (int click = 0; click < 3; click++) {
			shown++;
			pending.sent("example", Map.of("radius", Integer.toString(shown)));
			if (click == 1) {
				// The answer to the first edit and a catalog showing 9 arrive between the second and third click.
				server = 9;
				pending.answered();
				shown = Integer.parseInt(pending.overrides("example").getOrDefault("radius", Integer.toString(server)));
			}
		}
		assertEquals(11, shown);
		server = 11;
		pending.answered();
		pending.answered();
		assertEquals(11, Integer.parseInt(pending.overrides("example").getOrDefault("radius", Integer.toString(server))));
	}

	@Test
	void unansweredEditsExpire() {
		pending.sent("example", Map.of("mode", "FAST"));
		now.addAndGet(PendingEdits.TIMEOUT_MS + 1);
		assertEquals(Map.of(), pending.overrides("example"));
		assertFalse(pending.isPending());
		pending.answered(); // a late answer does not go negative
		pending.sent("example", Map.of("mode", "SILENT"));
		assertTrue(pending.isPending());
		pending.answered();
		assertFalse(pending.isPending());
	}

	@Test
	void clearedOnDisconnect() {
		pending.sent("example", Map.of("mode", "FAST"));
		pending.clear();
		assertFalse(pending.isPending());
		assertEquals(Map.of(), pending.overrides("example"));
	}
}
