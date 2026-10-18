package com.mejta.mejtautils.freeze;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandFilterTest {
	@Test
	void allowsOnlyListedRoots() {
		CommandFilter filter = CommandFilter.parse("msg, tell,W , r,");
		assertTrue(filter.isAllowed("/msg Mod I am here"));
		assertTrue(filter.isAllowed("w Mod hi"));
		assertTrue(filter.isAllowed("/minecraft:tell Mod hi"));
		assertFalse(filter.isAllowed("/spawn"));
		assertFalse(filter.isAllowed("/msgx a"));
		assertFalse(filter.isAllowed("/"));
		assertFalse(filter.isAllowed(""));
		assertFalse(CommandFilter.parse("").isAllowed("/msg a"));
	}
}
