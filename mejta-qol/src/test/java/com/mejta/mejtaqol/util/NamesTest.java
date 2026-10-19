package com.mejta.mejtaqol.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class NamesTest {
	@Test
	void normalizesToLowercaseAndChecksCharacters() {
		assertEquals(Optional.of("base_2"), Names.normalize("Base_2"));
		assertEquals(Optional.of("a-b"), Names.normalize("a-b"));
		assertTrue(Names.normalize("").isEmpty());
		assertTrue(Names.normalize(null).isEmpty());
		assertTrue(Names.normalize("my home").isEmpty());
		assertTrue(Names.normalize("../x").isEmpty());
		assertTrue(Names.normalize("x".repeat(17)).isEmpty());
		assertEquals(Optional.of("x".repeat(16)), Names.normalize("x".repeat(16)));
	}

	@Test
	void parsesDimensionLists() {
		assertEquals(List.of("minecraft:the_end", "mymod:sky"), Names.list(" the_end, ,MYMOD:sky,the_end ", true));
		assertEquals(List.of("a", "b"), Names.list("a,b", false));
		assertEquals(List.of(), Names.list("", true));
		assertTrue(Names.isDimensionList(""));
		assertTrue(Names.isDimensionList("minecraft:overworld, the_nether"));
		assertFalse(Names.isDimensionList("bad dimension"));
		assertFalse(Names.isDimensionList("a:b:c"));
		assertFalse(Names.isDimensionList(null));
	}
}
