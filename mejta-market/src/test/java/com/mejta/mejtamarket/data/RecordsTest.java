package com.mejta.mejtamarket.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

class RecordsTest {
	@Test
	void lotsAndOrdersSurviveJson() {
		Lot lot = Lot.pendingListing("0a1b2c3d", JsonParser.parseString("{\"id\":\"minecraft:dirt\"}"), "minecraft:dirt", "Dirt", "BLOCKS", 70,
				new UUID(0, 1), "Steve", 700, 1, 2, Map.of(3, JsonParser.parseString("{\"id\":\"minecraft:dirt\",\"count\":64}")));
		assertEquals(lot, Lot.fromJson(lot.toJson()).orElseThrow());
		assertEquals(lot.listed().sold(new UUID(0, 2), "Alex", 5), Lot.fromJson(lot.listed().sold(new UUID(0, 2), "Alex", 5).toJson()).orElseThrow());
		BuyOrder order = BuyOrder.open("0a1b2c3e", new UUID(0, 1), "Steve", "minecraft:dirt", "Dirt", "BLOCKS", 64, 5, 1, 2).withFill("x", 10);
		assertEquals(order, BuyOrder.fromJson(order.toJson()).orElseThrow());
		assertEquals(54 * 5, order.escrow());
		assertThrows(IllegalArgumentException.class, () -> order.withFill("y", 55));
	}
}
