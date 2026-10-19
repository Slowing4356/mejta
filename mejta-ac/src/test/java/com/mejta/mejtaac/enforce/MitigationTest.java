package com.mejta.mejtaac.enforce;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.AcSettings.MitigationMode;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;

/** Mitigation: when the action behind a flag is undone, and the setback rules. */
class MitigationTest {
	private static Flag flag(Confidence c) {
		return new Flag("x", "y", c, 1.0, Map.of());
	}

	@Test
	void offByDefaultAndInOffModeNothingIsMitigated() {
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.DRY_RUN, MitigationMode.OFF, Confidence.HIGH, flag(Confidence.CERTAIN), Action.NONE));
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.ENFORCE, MitigationMode.OFF, Confidence.HIGH, flag(Confidence.CERTAIN), Action.NONE));
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.OFF, MitigationMode.ON, Confidence.LOW, flag(Confidence.CERTAIN), Action.NONE));
		assertFalse(EnforcementPolicy.mitigationActive(EnforcementMode.OFF, MitigationMode.ON));
		assertFalse(EnforcementPolicy.mitigationActive(EnforcementMode.ENFORCE, MitigationMode.OFF));
	}

	@Test
	void aSingleConfidentFlagIsEnoughInDryRunAndEnforce() {
		// Independent of violation levels and kicks: no threshold involved.
		assertTrue(EnforcementPolicy.mitigates(EnforcementMode.DRY_RUN, MitigationMode.ON, Confidence.HIGH, flag(Confidence.HIGH), Action.NONE));
		assertTrue(EnforcementPolicy.mitigates(EnforcementMode.ENFORCE, MitigationMode.ON, Confidence.HIGH, flag(Confidence.CERTAIN), Action.NONE));
		assertTrue(EnforcementPolicy.mitigates(EnforcementMode.DRY_RUN, MitigationMode.ON, Confidence.HIGH, flag(Confidence.HIGH), Action.WOULD_KICK));
	}

	@Test
	void flagsBelowTheMinimumConfidenceAreNeverMitigated() {
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.ENFORCE, MitigationMode.ON, Confidence.HIGH, flag(Confidence.MEDIUM), Action.NONE));
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.DRY_RUN, MitigationMode.ON, Confidence.CERTAIN, flag(Confidence.HIGH), Action.NONE));
		assertTrue(EnforcementPolicy.mitigates(EnforcementMode.DRY_RUN, MitigationMode.ON, Confidence.MEDIUM, flag(Confidence.MEDIUM), Action.NONE));
	}

	@Test
	void aKickNeedsNoMitigation() {
		assertFalse(EnforcementPolicy.mitigates(EnforcementMode.ENFORCE, MitigationMode.ON, Confidence.HIGH, flag(Confidence.HIGH), Action.KICK));
	}

	@Test
	void mitigationDoesNotChangeKickDecisions() {
		// The kick decision ignores the mitigation setting entirely.
		assertTrue(EnforcementPolicy.decide(EnforcementMode.DRY_RUN, Confidence.HIGH, flag(Confidence.HIGH), 4.9, 5) == Action.NONE);
		assertTrue(EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, flag(Confidence.HIGH), 5, 5) == Action.KICK);
	}

	@Test
	void safePositionsAreOnlyCleanConfirmedTicks() {
		assertTrue(Setbacks.isSafeTick(false, false, false));
		assertFalse(Setbacks.isSafeTick(true, false, false), "a prediction building up an excess");
		assertFalse(Setbacks.isSafeTick(false, true, false), "a movement flag in this tick");
		assertFalse(Setbacks.isSafeTick(false, false, true), "vehicle, teleport, dimension change, sleeping");
	}

	@Test
	void setbacksNeedATargetAndAreRateLimited() {
		long ms = 1_000_000L;
		assertFalse(Setbacks.allowed(false, Double.NaN, Long.MIN_VALUE, 0, false), "no safe position known");
		assertTrue(Setbacks.allowed(true, 3.0, Long.MIN_VALUE, 0, false));
		assertFalse(Setbacks.allowed(true, 3.0, 0, 100 * ms, false), "within 250 ms of the previous one");
		assertTrue(Setbacks.allowed(true, 3.0, 0, 250 * ms, false));
	}

	@Test
	void noSetbackWhileATeleportIsUnconfirmedOrToAStaleTarget() {
		assertFalse(Setbacks.allowed(true, 3.0, Long.MIN_VALUE, 0, true), "vanilla is already correcting the player");
		assertFalse(Setbacks.allowed(true, Setbacks.MAX_DISTANCE + 0.1, Long.MIN_VALUE, 0, false));
		assertTrue(Setbacks.allowed(true, Setbacks.MAX_DISTANCE, Long.MIN_VALUE, 0, false));
		assertFalse(Setbacks.allowed(true, Double.NaN, Long.MIN_VALUE, 0, false));
	}
}
