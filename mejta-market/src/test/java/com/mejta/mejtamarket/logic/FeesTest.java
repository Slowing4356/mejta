package com.mejta.mejtamarket.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeesTest {
	@Test
	void percentagesRoundHalfUpAndNeverExceedTheAmount() {
		assertEquals(250, Fees.percentOf(5000, 5.0));
		assertEquals(1, Fees.percentOf(10, 5.0));
		assertEquals(0, Fees.percentOf(9, 5.0));
		assertEquals(4750, Fees.proceeds(5000, 5.0));
		assertEquals(0, Fees.proceeds(10, 100.0));
		assertEquals(-1, Fees.total(Long.MAX_VALUE, 2));
	}

	@Test
	void categoriesAndSearch() {
		assertEquals(Categories.Category.ORES, Categories.of(new Categories.ItemFacts("minecraft:iron_ingot", false, false, false, false)));
		assertEquals(Categories.Category.BLOCKS, Categories.of(new Categories.ItemFacts("minecraft:iron_door", true, false, false, false)));
		assertEquals(Categories.Category.TOOLS, Categories.of(new Categories.ItemFacts("minecraft:iron_pickaxe", false, false, false, true)));
		assertEquals(Categories.Category.FARMING, Categories.of(new Categories.ItemFacts("minecraft:golden_carrot", false, true, false, false)));
		assertEquals(Categories.Category.OTHER, Categories.of(new Categories.ItemFacts("minecraft:pig_spawn_egg", false, false, false, false)));
		assertEquals(true, Search.matches("iron pick", "minecraft:iron_pickaxe", "Iron Pickaxe", "Steve"));
		assertEquals(false, Search.matches("diamond", "minecraft:iron_pickaxe", "Iron Pickaxe", "Steve"));
	}
}
