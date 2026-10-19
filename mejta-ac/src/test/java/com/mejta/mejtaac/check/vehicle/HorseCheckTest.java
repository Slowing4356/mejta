package com.mejta.mejtaac.check.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

class HorseCheckTest {
	private static final double GRAVITY = 0.08;
	private static final double TOLERANCE = HorseCheck.DEFAULT_SPEED_TOLERANCE;

	/**
	 * A ridden mount on flat ground at y = 0, written out independently of the check
	 * (LivingEntity.travelInAir with the mount's attributes): a jump sets the vertical speed and adds a
	 * forward boost, then acceleration, the move (landing on the ground), gravity and friction.
	 */
	private static final class Mount {
		final double speedAttr;
		final double jumpStrength;
		final boolean camel;
		double friction = 0.6;
		double x;
		double y;
		double vx;
		double vy;
		boolean onGround = true;
		boolean jumpNow;
		double forward = 1.0;
		final List<double[]> ticks = new ArrayList<>(); // dy, speed, groundNow, landed
		final HorseCheck.State state = new HorseCheck.State();
		boolean groundOneAgo = true;
		boolean groundTwoAgo = true;

		Mount(double speedAttr, double jumpStrength, boolean camel) {
			this.speedAttr = speedAttr;
			this.jumpStrength = jumpStrength;
			this.camel = camel;
		}

		/** One client tick; returns the check's verdict on it. */
		Optional<Flag> tick() {
			double y0 = y;
			double x0 = x;
			if (onGround && jumpNow) {
				if (camel) {
					vx += 22.2222 * speedAttr;
					vy = 1.4285 * jumpStrength;
				} else {
					vy = jumpStrength;
					vx += 0.4;
				}
			}
			jumpNow = false;
			double ridden = camel ? speedAttr + 0.1 : speedAttr;
			double accel = onGround ? ridden * 0.21600002 / (friction * friction * friction) : ridden * 0.1;
			vx += forward * accel;
			double f = onGround ? friction : 1.0;
			boolean landed = false;
			x += vx;
			y += vy;
			if (y <= 0) {
				landed = y < 0 || vy < 0;
				y = 0;
				vy = 0;
				onGround = true;
			} else {
				onGround = false;
			}
			vy = (vy - GRAVITY) * 0.98;
			vx *= f * 0.91;
			if (onGround) {
				vy = 0;
			}
			return judge(y - y0, Math.abs(x - x0), landed);
		}

		Optional<Flag> judge(double dy, double speed, boolean pathBlocked) {
			boolean groundBefore = groundOneAgo;
			double decay = groundTwoAgo && groundOneAgo ? friction * 0.91 : 0.91;
			double accel = HorseCheck.acceleration(HorseCheck.riddenSpeed(speedAttr, camel), groundBefore || onGround, friction);
			boolean tookOff = groundBefore && !onGround;
			double boost = tookOff ? HorseCheck.jumpBoost(speedAttr, camel) : 0;
			HorseCheck.Tick t = new HorseCheck.Tick(dy, speed, groundBefore, onGround, pathBlocked,
					HorseCheck.maxTakeOff(jumpStrength, 0, camel), GRAVITY, decay, accel, boost);
			groundTwoAgo = groundOneAgo;
			groundOneAgo = onGround;
			return HorseCheck.onClientTick(state, t, TOLERANCE, false);
		}
	}

	private static List<Flag> run(Mount m, int ticks, int jumpEvery) {
		List<Flag> flags = new ArrayList<>();
		for (int i = 0; i < ticks; i++) {
			if (jumpEvery > 0 && i % jumpEvery == 0) {
				m.jumpNow = true;
			}
			m.tick().ifPresent(flags::add);
		}
		return flags;
	}

	@Test
	void fastestHorseGallopingAndJumpingIsNeverFlagged() {
		Mount m = new Mount(0.3375, 1.0, false);
		List<Flag> flags = run(m, 400, 37);
		assertTrue(flags.isEmpty(), flags.toString());
		assertTrue(m.x > 200, "the horse really galloped: " + m.x);
	}

	@Test
	void gallopOnIceAndTurningBackIsNeverFlagged() {
		Mount m = new Mount(0.3375, 0.7, false);
		m.friction = 0.989; // blue ice
		List<Flag> flags = run(m, 200, 0);
		m.forward = -0.25; // braking: backwards input is a quarter
		for (int i = 0; i < 100; i++) {
			m.tick().ifPresent(flags::add);
		}
		assertTrue(flags.isEmpty(), flags.toString());
	}

	@Test
	void camelDashesAreNeverFlagged() {
		Mount m = new Mount(0.09, 0.42, true);
		List<Flag> flags = run(m, 300, 60); // a dash every 3 s (vanilla cooldown 55 ticks)
		assertTrue(flags.isEmpty(), flags.toString());
	}

	@Test
	void walkingOffALedgeIsNeverFlagged() {
		Mount m = new Mount(0.25, 0.6, false);
		m.y = 6;
		m.onGround = false;
		List<Flag> flags = run(m, 80, 0);
		assertTrue(flags.isEmpty(), flags.toString());
	}

	@Test
	void hoverAndClimbAreFlaggedMediumOnly() {
		for (double vy : new double[] {0.0, 0.2}) {
			HorseCheck.State s = new HorseCheck.State();
			List<Flag> flags = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				HorseCheck.Tick t = new HorseCheck.Tick(vy, 0.2, false, false, false, 1.0, GRAVITY, 0.91, 0.03, 0);
				HorseCheck.onClientTick(s, t, TOLERANCE, false).ifPresent(flags::add);
			}
			assertFalse(flags.isEmpty(), "vy " + vy);
			assertEquals(vy > 0 ? "ascending" : "hovering", flags.get(0).reason());
			assertTrue(flags.stream().allMatch(f -> f.confidence() == Confidence.MEDIUM));
		}
	}

	@Test
	void takeOffHigherThanJumpStrengthIsFlagged() {
		HorseCheck.State s = new HorseCheck.State();
		HorseCheck.Tick ground = new HorseCheck.Tick(0, 0.3, true, true, false, 1.0, GRAVITY, 0.546, 0.3375, 0);
		assertTrue(HorseCheck.onClientTick(s, ground, TOLERANCE, false).isEmpty());
		HorseCheck.Tick fine = new HorseCheck.Tick(1.0, 0.3, true, false, false, 1.0, GRAVITY, 0.546, 0.3375, 0.4);
		assertTrue(HorseCheck.onClientTick(new HorseCheck.State(), fine, TOLERANCE, false).isEmpty());
		HorseCheck.Tick high = new HorseCheck.Tick(1.5, 0.3, true, false, false, 1.0, GRAVITY, 0.546, 0.3375, 0.4);
		Optional<Flag> flag = HorseCheck.onClientTick(s, high, TOLERANCE, false);
		assertEquals("too_high", flag.orElseThrow().reason());
		assertEquals(Confidence.MEDIUM, flag.get().confidence());
	}

	@Test
	void speedBeyondTheAttributesIsFlagged() {
		HorseCheck.State s = new HorseCheck.State();
		List<Flag> flags = new ArrayList<>();
		double accel = HorseCheck.acceleration(0.3375, true, 0.6);
		for (int i = 0; i < 40; i++) {
			HorseCheck.Tick t = new HorseCheck.Tick(0, 1.2, true, true, false, 1.0, GRAVITY, 0.546, accel, 0);
			HorseCheck.onClientTick(s, t, TOLERANCE, false).ifPresent(flags::add);
		}
		assertFalse(flags.isEmpty());
		assertEquals("too_fast", flags.get(0).reason());
	}

	@Test
	void untrustedTicksResetEverything() {
		HorseCheck.State s = new HorseCheck.State();
		for (int i = 0; i < 100; i++) {
			HorseCheck.Tick t = new HorseCheck.Tick(0.5, 5.0, true, false, false, 0.4, GRAVITY, 0.91, 0.03, 0);
			assertTrue(HorseCheck.onClientTick(s, t, TOLERANCE, true).isEmpty());
		}
		assertTrue(HorseCheck.expectedDy(s, GRAVITY).isEmpty());
	}

	@Test
	void unloadedChunkFallIsIgnored() {
		HorseCheck.State s = new HorseCheck.State();
		for (int i = 0; i < 60; i++) {
			HorseCheck.Tick t = new HorseCheck.Tick(-0.098, 0, false, false, false, 1.0, GRAVITY, 0.91, 0.03, 0);
			assertTrue(HorseCheck.onClientTick(s, t, TOLERANCE, false).isEmpty());
		}
	}

	@Test
	void allowanceKeepsTheHighestValueForAWhile() {
		HorseCheck.Allowance a = new HorseCheck.Allowance();
		long s = 1_000_000_000L;
		assertEquals(0.3, a.update(0.3, 0, 2 * s));
		assertEquals(0.3, a.update(0.2, s, 2 * s), "speed effect ended on the server; the client may still have it");
		assertEquals(0.2, a.update(0.2, 3 * s, 2 * s));
		assertEquals(0.4, a.update(0.4, 3 * s, 2 * s), "a higher value counts at once");
	}

	@Test
	void camelAllowancesOnlyForCamels() {
		assertEquals(0.4, HorseCheck.jumpBoost(0.3375, false));
		assertEquals(22.2222 * 0.09, HorseCheck.jumpBoost(0.09, true), 1e-9);
		assertEquals(1.0, HorseCheck.maxTakeOff(0.9, 0.1, false), 1e-9);
		assertEquals(1.4285 * 0.42, HorseCheck.maxTakeOff(0.42, 0, true), 1e-9);
		assertEquals(0.19, HorseCheck.riddenSpeed(0.09, true), 1e-9);
	}
}
