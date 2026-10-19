package com.mejta.mejtaqol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.config.Setting;

class QolSettingsTest {
	@Test
	void everyDefaultIsValidAndKeysAreUnique() {
		Set<String> keys = new HashSet<>();
		for (Setting<?> s : QolSettings.SPEC.settings()) {
			assertTrue(s.isValid(s.defaultValue()), s.key());
			assertTrue(keys.add(s.key()), "duplicate " + s.key());
			assertFalse(s.description().isBlank(), s.key());
			assertFalse(s.isSecret(), s.key());
		}
		assertEquals(51, keys.size(), "a setting was added or removed: update the README's settings table");
	}

	@Test
	void validatorsRejectBadValues() {
		assertFalse(QolSettings.RTP_CENTER.isValid("middle"));
		assertTrue(QolSettings.RTP_CENTER.isValid("0,0"));
		assertFalse(QolSettings.RTP_DIMENSIONS.isValid("not a dimension"));
		assertTrue(QolSettings.HOMES_BLOCKED_DIMENSIONS.isValid("the_end, minecraft:the_nether"));
		assertFalse(QolSettings.MOTD_TEXT.isValid(""));
		assertFalse(QolSettings.AFK_KICK_MESSAGE.isValid("x".repeat(201)));
		assertTrue(QolSettings.RTP_ENABLED.isExperimental());
	}
}
