package com.mejta.mejtacorpse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.config.Setting;

class CorpseSettingsTest {
	@Test
	void defaultsAreValidAndKeysUnique() {
		Set<String> keys = new HashSet<>();
		for (Setting<?> s : CorpseSettings.SPEC.settings()) {
			assertTrue(s.isValid(s.defaultValue()), s.key());
			assertTrue(keys.add(s.key()), "duplicate " + s.key());
			assertFalse(s.description().isBlank(), s.key());
		}
		assertEquals(11, keys.size(), "a setting was added or removed: update the README's settings table");
		assertEquals(-1, CorpseSettings.PUBLIC_AFTER_MINUTES.defaultValue(), "only the owner by default");
		assertEquals(0, CorpseSettings.EXPIRE_MINUTES.defaultValue(), "corpses never expire by default");
		assertTrue(CorpseSettings.SHOW_ARMOR.defaultValue(), "corpses show their armour by default");
	}
}
