package com.mejta.mejtaqol.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import com.mejta.mejtaqol.location.Location;

class WarpBookTest {
	static final Location SPAWN = new Location("minecraft:overworld", 0, 70, 0, 0, 0);

	@Test
	void setGetDeleteAndRoundTrip() {
		WarpBook book = new WarpBook();
		assertTrue(book.set("Shop", SPAWN, "minecraft:emerald", "Admin"));
		assertTrue(book.set("arena", SPAWN, "", null));
		assertFalse(book.set("bad name", SPAWN, null, null));
		assertEquals("minecraft:emerald", book.get("SHOP").orElseThrow().icon());
		assertEquals(WarpBook.DEFAULT_ICON, book.get("arena").orElseThrow().icon());
		assertEquals("", book.get("arena").orElseThrow().creator());

		WarpBook copy = WarpBook.fromJson(book.toJson());
		assertEquals(book.all(), copy.all());
		assertTrue(copy.delete("shop"));
		assertEquals(1, copy.size());
	}

	@Test
	void readsOldOrDamagedFiles() {
		WarpBook book = WarpBook.fromJson(JsonParser.parseString("""
				{"warps": {"a": {"dimension": "minecraft:overworld", "x": 1, "y": 2, "z": 3},
				           "b": {"dimension": "minecraft:overworld", "x": 1, "y": 2, "z": 3, "icon": {"x": 1}},
				           "c": {"x": 1}}}""").getAsJsonObject());
		assertEquals(2, book.size());
		assertEquals(WarpBook.DEFAULT_ICON, book.get("a").orElseThrow().icon());
		assertEquals(WarpBook.DEFAULT_ICON, book.get("b").orElseThrow().icon());
	}
}
