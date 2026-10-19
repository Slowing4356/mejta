package com.mejta.mejtaqol.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import com.mejta.mejtaqol.home.HomeBook.SetResult;
import com.mejta.mejtaqol.location.Location;

class HomeBookTest {
	static Location at(double x) {
		return new Location("minecraft:overworld", x, 64, 0, 0, 0);
	}

	@Test
	void limitAppliesToNewHomesOnly() {
		HomeBook book = new HomeBook();
		assertEquals(SetResult.CREATED, book.set("Base", at(1), 2));
		assertEquals(SetResult.CREATED, book.set("farm", at(2), 2));
		assertEquals(SetResult.LIMIT_REACHED, book.set("mine", at(3), 2));
		assertEquals(SetResult.MOVED, book.set("BASE", at(4), 2));
		assertEquals(4, book.get("base").orElseThrow().x());
		// After the limit was lowered, existing homes can still be moved.
		assertEquals(SetResult.MOVED, book.set("farm", at(5), 1));
		assertEquals(SetResult.LIMIT_REACHED, book.set("x", at(6), 0));
		assertEquals(SetResult.INVALID_NAME, book.set("no spaces", at(6), 10));
	}

	@Test
	void deleteAndSortedNames() {
		HomeBook book = new HomeBook();
		book.set("zeta", at(1), 5);
		book.set("alpha", at(2), 5);
		assertEquals(List.of("alpha", "zeta"), List.copyOf(book.all().keySet()));
		assertTrue(book.delete("ZETA"));
		assertFalse(book.delete("zeta"));
		assertFalse(book.delete("bad name"));
		assertEquals(1, book.size());
	}

	@Test
	void defaultHomeIsTheNamedOneOrTheOnlyOne() {
		HomeBook book = new HomeBook();
		assertTrue(book.defaultHome("home").isEmpty());
		book.set("base", at(1), 5);
		assertEquals("base", book.defaultHome("home").orElseThrow().getKey());
		book.set("farm", at(2), 5);
		assertTrue(book.defaultHome("home").isEmpty(), "two homes, none called home");
		book.set("home", at(3), 5);
		assertEquals("home", book.defaultHome("home").orElseThrow().getKey());
	}

	@Test
	void roundTripsAndSkipsBadEntries() {
		HomeBook book = new HomeBook();
		book.set("base", at(1), 5);
		book.set("farm", at(2), 5);
		HomeBook copy = HomeBook.fromJson(book.toJson());
		assertEquals(book.all(), copy.all());

		HomeBook messy = HomeBook.fromJson(JsonParser.parseString("""
				{"homes": {"ok": {"dimension": "minecraft:overworld", "x": 1, "y": 2, "z": 3},
				           "bad name": {"dimension": "minecraft:overworld", "x": 1, "y": 2, "z": 3},
				           "broken": {"x": 1}, "notobject": 5}}""").getAsJsonObject());
		assertEquals(List.of("ok"), List.copyOf(messy.all().keySet()));
		assertEquals(0, HomeBook.fromJson(null).size());
		assertEquals(0, HomeBook.fromJson(JsonParser.parseString("{\"homes\": []}").getAsJsonObject()).size());
	}
}
