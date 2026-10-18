package com.mejta.mejtaac.check.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

class JumpCheckTest {
	private static final double JUMP = 0.42;

	@Test
	void normalJumpIsAllowed() {
		JumpCheck.State s = new JumpCheck.State();
		for (int i = 0; i < 100; i++) {
			assertTrue(JumpCheck.onTakeOff(s, JUMP, JUMP, 0, i * 12L).isEmpty());
		}
	}

	@Test
	void floatRoundingIsTolerated() {
		assertTrue(JumpCheck.onTakeOff(new JumpCheck.State(), JUMP + 0.029, JUMP, 0, 0).isEmpty());
	}

	@Test
	void jumpBoostRaisesTheLimit() {
		double jumpBoostII = JUMP + 0.2;
		assertTrue(JumpCheck.onTakeOff(new JumpCheck.State(), jumpBoostII, jumpBoostII, 0, 0).isEmpty());
	}

	@Test
	void slimeBounceUpToTheLandingSpeedIsAllowed() {
		assertTrue(JumpCheck.onTakeOff(new JumpCheck.State(), 1.1, JUMP, 1.2, 0).isEmpty());
	}

	@Test
	void firstHighJumpIsOnlyMedium() {
		Optional<Flag> flag = JumpCheck.onTakeOff(new JumpCheck.State(), 0.7, JUMP, 0, 0);
		assertTrue(flag.isPresent());
		assertEquals(Confidence.MEDIUM, flag.get().confidence());
		assertEquals("too_high", flag.get().reason());
		assertEquals(0.5, flag.get().vlIncrease());
	}

	@Test
	void repeatedClearHighJumpsBecomeHigh() {
		JumpCheck.State s = new JumpCheck.State();
		assertEquals(Confidence.MEDIUM, JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 0).orElseThrow().confidence());
		assertEquals(Confidence.MEDIUM, JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 20).orElseThrow().confidence());
		assertEquals(Confidence.HIGH, JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 40).orElseThrow().confidence());
	}

	@Test
	void smallExcessNeverBecomesHigh() {
		JumpCheck.State s = new JumpCheck.State();
		for (int i = 0; i < 10; i++) {
			assertEquals(Confidence.MEDIUM, JumpCheck.onTakeOff(s, JUMP + 0.06, JUMP, 0, i * 20L).orElseThrow().confidence());
		}
	}

	@Test
	void strikesExpireAfterTheWindow() {
		JumpCheck.State s = new JumpCheck.State();
		JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 0);
		JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 20);
		Flag late = JumpCheck.onTakeOff(s, 0.7, JUMP, 0, 20 + JumpCheck.STRIKE_WINDOW_TICKS + 1).orElseThrow();
		assertEquals(Confidence.MEDIUM, late.confidence());
		assertEquals(1, late.measurements().get("strikes"));
	}
}
