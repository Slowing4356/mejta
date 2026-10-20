package com.mejta.mejtasmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.config.Setting;

class SmpSettingsTest {
	@Test
	void defaultsAreValidAndKeysUnique() {
		Set<String> keys = new HashSet<>();
		for (Setting<?> s : SmpSettings.SPEC.settings()) {
			assertTrue(s.isValid(s.defaultValue()), s.key());
			assertTrue(keys.add(s.key()), "duplicate " + s.key());
			assertFalse(s.description().isBlank(), s.key());
			assertTrue(s.key().contains("."), "every key has a group prefix for the settings menu: " + s.key());
		}
		assertEquals(24, keys.size(), "a setting was added or removed: update the README's settings table");
		assertTrue(SmpSettings.DROPS_STACK.isExperimental(), "piles bigger than a stack are experimental");
	}
}
