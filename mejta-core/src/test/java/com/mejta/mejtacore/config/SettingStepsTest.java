package com.mejta.mejtacore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.config.Setting;

class SettingStepsTest {
	enum Mode {
		NORMAL,
		FAST,
		SILENT
	}

	static final Setting<Integer> RADIUS = Setting.integer("radius", 8, 1, 64);
	static final Setting<Integer> ANY_INT = Setting.integer("count", 0);
	static final Setting<Double> SPEED = Setting.decimal("speed", 1.0, 0.1, 5.0);
	static final Setting<Double> CHANCE = Setting.decimal("chance", 0.5, 0.0, 1.0);
	static final Setting<Double> PERCENT = Setting.decimal("percent", 50, 0, 100);
	static final Setting<Double> ANY_DECIMAL = Setting.decimal("factor", 1.0);
	static final Setting<Mode> MODE = Setting.enumeration("mode", Mode.NORMAL);
	static final Setting<String> LEVEL = Setting.choice("level", "low", List.of("low", "mid", "high"));

	@Test
	void booleansToggle() {
		Setting<Boolean> enabled = Setting.bool("enabled", true);
		assertEquals(false, SettingSteps.next(enabled, true, false, false));
		assertEquals(true, SettingSteps.next(enabled, false, true, true));
	}

	@Test
	void integersStepAndClamp() {
		assertEquals(9, SettingSteps.next(RADIUS, 8, false, false));
		assertEquals(7, SettingSteps.next(RADIUS, 8, true, false));
		assertEquals(18, SettingSteps.next(RADIUS, 8, false, true));
		assertEquals(1, SettingSteps.next(RADIUS, 8, true, true), "clamped to the minimum");
		assertEquals(64, SettingSteps.next(RADIUS, 60, false, true), "clamped to the maximum");
		assertFalse(SettingSteps.changes(64, SettingSteps.next(RADIUS, 64, false, false)));
		assertEquals("Already at the maximum (64)", SettingSteps.unchangedReason(RADIUS, 64, false));
		assertEquals(Integer.MAX_VALUE, SettingSteps.nextInteger(ANY_INT, Integer.MAX_VALUE - 3, false, true), "no overflow");
	}

	@Test
	void decimalStepFitsTheRange() {
		assertEquals(0.1, SettingSteps.decimalStep(SPEED));
		assertEquals(0.01, SettingSteps.decimalStep(CHANCE));
		assertEquals(1.0, SettingSteps.decimalStep(PERCENT));
		assertEquals(0.1, SettingSteps.decimalStep(ANY_DECIMAL));
	}

	@Test
	void decimalsUseExactArithmetic() {
		assertEquals(1.1, SettingSteps.next(SPEED, 1.0, false, false));
		assertEquals(1.2, SettingSteps.next(SPEED, 1.1, false, false), "not 1.2000000000000002");
		assertEquals(2.0, SettingSteps.next(SPEED, 1.0, false, true));
		assertEquals(0.1, SettingSteps.next(SPEED, 0.5, true, true), "clamped to the minimum");
		assertEquals(0.51, SettingSteps.next(CHANCE, 0.5, false, false));
		assertEquals(0.4, SettingSteps.next(CHANCE, 0.5, true, true));
		assertEquals(1.0, SettingSteps.next(CHANCE, 0.95, false, true));
	}

	@Test
	void enumsAndChoicesCycleBothWays() {
		assertEquals(Mode.FAST, SettingSteps.next(MODE, Mode.NORMAL, false, false));
		assertEquals(Mode.SILENT, SettingSteps.next(MODE, Mode.NORMAL, true, false), "wraps backwards");
		assertEquals(Mode.NORMAL, SettingSteps.next(MODE, Mode.SILENT, false, false), "wraps forwards");
		assertEquals("mid", SettingSteps.next(LEVEL, "low", false, false));
		assertEquals("high", SettingSteps.next(LEVEL, "low", true, false));
	}

	@Test
	void cyclingSkipsValuesTheValidatorRefuses() {
		Setting<Mode> noFast = Setting.enumeration("mode", Mode.NORMAL).validatedBy(m -> m != Mode.FAST);
		assertEquals(Mode.SILENT, SettingSteps.next(noFast, Mode.NORMAL, false, false));
		Setting<Mode> onlyNormal = Setting.enumeration("mode", Mode.NORMAL).validatedBy(m -> m == Mode.NORMAL);
		assertEquals(Mode.NORMAL, SettingSteps.next(onlyNormal, Mode.NORMAL, false, false));
		assertFalse(SettingSteps.changes(Mode.NORMAL, SettingSteps.next(onlyNormal, Mode.NORMAL, false, false)));
	}

	@Test
	void textCannotBeStepped() {
		assertNull(SettingSteps.next(Setting.string("greeting", "hi"), "hi", false, false));
		assertFalse(SettingSteps.changes("hi", null));
		assertTrue(SettingSteps.changes(1, 2));
	}

	@Test
	void realRange() {
		assertTrue(SettingSteps.hasRealRange(RADIUS));
		assertFalse(SettingSteps.hasRealRange(ANY_INT));
		assertFalse(SettingSteps.hasRealRange(ANY_DECIMAL));
		assertFalse(SettingSteps.hasRealRange(MODE));
	}
}
