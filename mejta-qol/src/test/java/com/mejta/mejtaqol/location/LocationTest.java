package com.mejta.mejtaqol.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class LocationTest {
	@Test
	void roundTripsThroughJson() {
		Location loc = new Location("minecraft:the_nether", 10.5, 64, -3.25, 90f, -12.5f);
		assertEquals(loc, Location.fromJson(loc.toJson()).orElseThrow());
	}

	@Test
	void rejectsMalformedJson() {
		assertTrue(Location.fromJson(null).isEmpty());
		assertTrue(Location.fromJson(JsonParser.parseString("[1,2]")).isEmpty());
		assertTrue(Location.fromJson(JsonParser.parseString("{\"x\":1,\"y\":2,\"z\":3}")).isEmpty(), "no dimension");
		assertTrue(Location.fromJson(JsonParser.parseString("{\"dimension\":\"minecraft:overworld\",\"x\":\"a\",\"y\":2,\"z\":3}")).isEmpty());
		assertTrue(Location.fromJson(JsonParser.parseString("{\"dimension\":\"minecraft:overworld\",\"x\":\"NaN\",\"y\":2,\"z\":3}")).isEmpty());
	}

	@Test
	void rotationIsOptional() {
		JsonObject o = new JsonObject();
		o.addProperty("dimension", "minecraft:overworld");
		o.addProperty("x", 1);
		o.addProperty("y", 2);
		o.addProperty("z", 3);
		Location loc = Location.fromJson(o).orElseThrow();
		assertEquals(0f, loc.yaw());
		assertEquals(0f, loc.pitch());
	}

	@Test
	void describesBlockCoordinates() {
		assertEquals("-1, 64, 3 (overworld)", new Location("minecraft:overworld", -0.5, 64.9, 3.99, 0, 0).describe());
		assertEquals("0, 0, 0 (mymod:sky)", new Location("mymod:sky", 0, 0, 0, 0, 0).describe());
	}

	@Test
	void distanceAcrossDimensionsIsInfinite() {
		Location a = new Location("minecraft:overworld", 0, 0, 0, 0, 0);
		assertEquals(25, a.distanceSquared(new Location("minecraft:overworld", 3, 4, 0, 0, 0)));
		assertEquals(Double.POSITIVE_INFINITY, a.distanceSquared(new Location("minecraft:the_end", 0, 0, 0, 0, 0)));
	}

	@Test
	void requiresDimensionAndFiniteValues() {
		assertThrows(IllegalArgumentException.class, () -> new Location("", 0, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Location("minecraft:overworld", Double.NaN, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Location("minecraft:overworld", 0, 0, 0, Float.POSITIVE_INFINITY, 0));
	}
}
