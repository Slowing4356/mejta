package com.mejta.mejtacorpse.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SlotLayoutTest {
	@Test
	void everySlotMapsBothWaysExactlyOnce() {
		Set<Integer> seen = new HashSet<>();
		for (int d = 0; d < SlotLayout.SIZE; d++) {
			int inv = SlotLayout.toInventory(d).orElseThrow();
			assertTrue(seen.add(inv), "inventory slot " + inv + " shown twice");
			assertEquals(d, SlotLayout.toDisplay(inv).orElseThrow(), "round trip of menu slot " + d);
		}
		assertEquals(SlotLayout.MAX_INVENTORY_SLOTS, seen.size());
	}

	@Test
	void layoutMatchesTheInventoryScreen() {
		assertEquals(9, SlotLayout.toInventory(0).orElseThrow(), "top left is the first main-inventory slot");
		assertEquals(35, SlotLayout.toInventory(26).orElseThrow());
		assertEquals(0, SlotLayout.toInventory(27).orElseThrow(), "hotbar on row 4");
		assertEquals(39, SlotLayout.toInventory(36).orElseThrow(), "helmet first");
		assertEquals(36, SlotLayout.toInventory(39).orElseThrow(), "boots last");
		assertEquals(40, SlotLayout.toInventory(40).orElseThrow(), "offhand");
	}

	@Test
	void outOfRange() {
		assertTrue(SlotLayout.toInventory(-1).isEmpty());
		assertTrue(SlotLayout.toInventory(45).isEmpty(), "row 6 is buttons");
		assertTrue(SlotLayout.toDisplay(45).isEmpty());
		assertTrue(SlotLayout.toDisplay(-3).isEmpty());
	}
}
