package com.mejta.mejtaac.violation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.enforce.EnforcementPolicy;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;

/** Assess and decide: violation levels, throttling and the enforcement policy. */
class PipelineTest {
	private static final UUID PLAYER = UUID.randomUUID();

	@Test
	void violationLevelsAddAndDecay() {
		ViolationTracker t = new ViolationTracker();
		assertEquals(1.0, t.add(PLAYER, "a", 1.0, 2.0, 0));
		assertEquals(2.0, t.add(PLAYER, "a", 1.0, 2.0, 0));
		assertEquals(1.0, t.get(PLAYER, "a", 2.0, 30_000), 1e-9); // 2 per minute, 30 s later
		assertEquals(0.0, t.get(PLAYER, "a", 2.0, 600_000));
		assertEquals(0.0, t.get(PLAYER, "other", 2.0, 0));
	}

	@Test
	void violationLevelsAreSeparatePerCheckAndResettable() {
		ViolationTracker t = new ViolationTracker();
		t.add(PLAYER, "a", 1.0, 0, 0);
		t.add(PLAYER, "b", 3.0, 0, 0);
		assertEquals(Map.of("a", 1.0, "b", 3.0), t.snapshot(PLAYER, 0, 0));
		t.reset(PLAYER, "b");
		assertEquals(Map.of("a", 1.0), t.snapshot(PLAYER, 0, 0));
		t.clearCheck("a");
		assertEquals(Map.of(), t.snapshot(PLAYER, 0, 0));
	}

	@Test
	void throttleCountsSuppressedEvents() {
		Throttle th = new Throttle();
		assertEquals(0, th.tryPass(PLAYER, "k", 1000, 0));
		assertEquals(-1, th.tryPass(PLAYER, "k", 1000, 100));
		assertEquals(-1, th.tryPass(PLAYER, "k", 1000, 200));
		assertEquals(2, th.tryPass(PLAYER, "k", 1000, 1000));
	}

	private static Flag flag(Confidence c) {
		return new Flag("x", "y", c, 1.0, Map.of());
	}

	@Test
	void enforcementNeedsModeConfidenceAndThreshold() {
		assertEquals(Action.NONE, EnforcementPolicy.decide(EnforcementMode.OFF, Confidence.HIGH, flag(Confidence.CERTAIN), 99, 5));
		assertEquals(Action.NONE, EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, flag(Confidence.MEDIUM), 99, 5));
		assertEquals(Action.NONE, EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, flag(Confidence.HIGH), 4.9, 5));
		assertEquals(Action.KICK, EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, flag(Confidence.HIGH), 5, 5));
		assertEquals(Action.WOULD_KICK, EnforcementPolicy.decide(EnforcementMode.DRY_RUN, Confidence.HIGH, flag(Confidence.HIGH), 5, 5));
	}

	/** Runs flags through tracker and policy the way the pipeline does (min confidence HIGH, kick at 5). */
	private static Action feed(ViolationTracker t, Confidence confidence, double vl) {
		Flag flag = new Flag("x", "y", confidence, vl, Map.of());
		ViolationTracker.Levels levels = t.add(PLAYER, "x", vl, confidence.atLeast(Confidence.HIGH), 0, 0);
		return EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, flag, levels.enforceable(), 5);
	}

	@Test
	void mediumNoiseNeverAddsUpWithOneConfidentFlagToAKick() {
		ViolationTracker t = new ViolationTracker();
		for (int i = 0; i < 20; i++) {
			assertEquals(Action.NONE, feed(t, Confidence.MEDIUM, 0.5));
		}
		assertEquals(Action.NONE, feed(t, Confidence.HIGH, 1.0));
		assertEquals(11.0, t.get(PLAYER, "x", 0, 0)); // reported VL: every flag
		assertEquals(1.0, t.getEnforceable(PLAYER, "x", 0, 0)); // VL that can kick: confident flags only
		for (int i = 0; i < 3; i++) {
			assertEquals(Action.NONE, feed(t, Confidence.HIGH, 1.0));
		}
		assertEquals(Action.KICK, feed(t, Confidence.HIGH, 1.0)); // the fifth HIGH flag
	}

	@Test
	void enforceableLevelDecaysAndResetsWithTheTotal() {
		ViolationTracker t = new ViolationTracker();
		t.add(PLAYER, "x", 2.0, true, 2.0, 0);
		t.add(PLAYER, "x", 1.0, false, 2.0, 0);
		assertEquals(3.0, t.get(PLAYER, "x", 2.0, 0));
		assertEquals(1.0, t.getEnforceable(PLAYER, "x", 2.0, 30_000), 1e-9);
		assertEquals(0.0, t.getEnforceable(PLAYER, "x", 2.0, 60_000));
		t.reset(PLAYER, "x");
		assertEquals(0.0, t.getEnforceable(PLAYER, "x", 2.0, 0));
	}
}
