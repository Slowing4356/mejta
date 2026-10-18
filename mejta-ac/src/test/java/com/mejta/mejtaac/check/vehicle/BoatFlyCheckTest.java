package com.mejta.mejtaac.check.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

class BoatFlyCheckTest {
	private static final double GRAVITY = 0.04;

	private static List<Flag> run(BoatFlyCheck.State s, double[] dys) {
		List<Flag> flags = new ArrayList<>();
		for (double dy : dys) {
			BoatFlyCheck.onClientTick(s, dy, GRAVITY, false).ifPresent(flags::add);
		}
		return flags;
	}

	/** Vanilla in the air: dy(n) = dy(n-1) - gravity, no drag. */
	private static double[] vanillaFall(double startDy, int ticks) {
		double[] out = new double[ticks];
		double dy = startDy;
		for (int i = 0; i < ticks; i++) {
			out[i] = dy;
			dy -= GRAVITY;
		}
		return out;
	}

	@Test
	void vanillaFallOffALedgeIsNeverFlagged() {
		assertTrue(run(new BoatFlyCheck.State(), vanillaFall(0, 60)).isEmpty());
	}

	@Test
	void bubbleColumnLaunchThenGravityIsNeverFlagged() {
		// The launch tick itself is the first after a reset (water), so it only sets the velocity.
		assertTrue(run(new BoatFlyCheck.State(), vanillaFall(0.9, 60)).isEmpty());
	}

	@Test
	void hoveringIsFlaggedAndBecomesHigh() {
		BoatFlyCheck.State s = new BoatFlyCheck.State();
		List<Flag> flags = run(s, new double[40]);
		assertTrue(flags.size() >= 2, "hover flagged repeatedly");
		assertEquals("hovering", flags.get(0).reason());
		assertEquals(Confidence.MEDIUM, flags.get(0).confidence());
		assertEquals(Confidence.HIGH, flags.get(flags.size() - 1).confidence());
	}

	@Test
	void ascendingIsFlagged() {
		double[] up = new double[30];
		java.util.Arrays.fill(up, 0.3);
		List<Flag> flags = run(new BoatFlyCheck.State(), up);
		assertTrue(!flags.isEmpty());
		assertEquals("ascending", flags.get(0).reason());
	}

	@Test
	void slowGlideIsFlagged() {
		double[] glide = new double[40];
		java.util.Arrays.fill(glide, -0.05);
		assertEquals("falling_too_slowly", run(new BoatFlyCheck.State(), glide).get(0).reason());
	}

	@Test
	void untrustedTicksResetThePrediction() {
		BoatFlyCheck.State s = new BoatFlyCheck.State();
		for (int i = 0; i < 100; i++) {
			Optional<Flag> flag = BoatFlyCheck.onClientTick(s, 0.5, GRAVITY, true);
			assertTrue(flag.isEmpty());
		}
		assertTrue(BoatFlyCheck.expectedDy(s, GRAVITY).isEmpty());
	}

	@Test
	void flagsAreRateLimited() {
		BoatFlyCheck.State s = new BoatFlyCheck.State();
		List<Flag> flags = run(s, new double[100]);
		assertTrue(flags.size() <= 100 / BoatFlyCheck.FLAG_INTERVAL_TICKS + 1);
	}
}
