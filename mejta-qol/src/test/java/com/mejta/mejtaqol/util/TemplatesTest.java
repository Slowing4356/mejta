package com.mejta.mejtaqol.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplatesTest {
	@Test
	void fillsPlaceholdersAndColours() {
		assertEquals("\u00a76Hi Steve, \u00a7r3 online", Templates.render("&6Hi {player}, &r{online} online", Map.of("player", "Steve", "online", "3")));
		assertEquals("{unknown} stays", Templates.render("{unknown} stays", Map.of()));
		assertEquals("a & b &z", Templates.render("a && b &z", Map.of()));
		assertEquals("\u00a7a\u00a7lX", Templates.render("&A&LX", Map.of()));
	}

	@Test
	void valuesCannotInjectFormatting() {
		assertEquals("Hi \u00a7aEvilc$1\\", Templates.render("Hi &a{player}", Map.of("player", "Evil\u00a7c$1\\")));
		assertEquals("Hi &cEvil", Templates.render("Hi {player}", Map.of("player", "&cEvil")));
	}

	@Test
	void validatesTemplates() {
		assertTrue(Templates.valid("Welcome", 10));
		assertFalse(Templates.valid("", 10));
		assertFalse(Templates.valid("   ", 10));
		assertFalse(Templates.valid("x".repeat(11), 10));
		assertFalse(Templates.valid("two\nlines", 100));
		assertFalse(Templates.valid("raw \u00a7c code", 100));
		assertFalse(Templates.valid(null, 10));
	}
}
