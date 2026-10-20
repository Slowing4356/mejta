package com.mejta.mejtacore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.config.Setting;

class SettingGroupsTest {
	@Test
	void groupsByPrefixInOrder() {
		List<Setting<?>> settings = List.of(Setting.bool("enabled", true), Setting.integer("homes.max", 3, 0, 9),
				Setting.bool("tpa.enabled", true), Setting.integer("homes.cooldown_seconds", 3, 0, 9));
		Map<String, List<Setting<?>>> groups = SettingGroups.of(settings);
		assertEquals(List.of("general", "homes", "tpa"), List.copyOf(groups.keySet()));
		assertEquals(2, groups.get("homes").size());
		assertEquals("Public chat", SettingGroups.displayName("public_chat"));
	}

	@Test
	void onlyLargeConfigsAreGrouped() {
		List<Setting<?>> small = new ArrayList<>();
		for (int i = 0; i < 18; i++) {
			small.add(Setting.bool((i % 2 == 0 ? "a." : "b.") + "s" + i, true));
		}
		assertFalse(SettingGroups.grouped(small));
		List<Setting<?>> large = new ArrayList<>(small);
		large.add(Setting.bool("a.extra", true));
		assertTrue(SettingGroups.grouped(large));
		List<Setting<?>> oneGroup = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			oneGroup.add(Setting.bool("only.s" + i, true));
		}
		assertFalse(SettingGroups.grouped(oneGroup), "a single group is not worth a sub-menu");
	}
}
