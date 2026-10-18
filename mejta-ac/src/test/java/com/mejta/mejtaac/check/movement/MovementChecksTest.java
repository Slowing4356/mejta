package com.mejta.mejtaac.check.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/** Existing movement checks: fly, speed, timer and ground spoof. */
class MovementChecksTest {
	private static final double GRAVITY = 0.08;

	@Nested
	class Fly {
		private List<Flag> run(FlyCheck.State s, double[] dys) {
			List<Flag> flags = new ArrayList<>();
			for (double dy : dys) {
				FlyCheck.onClientTick(s, dy, GRAVITY, false).ifPresent(flags::add);
			}
			return flags;
		}

		/** A vanilla jump arc: move by vy, then vy = (vy - g) * 0.98. */
		private double[] arc(double vy, int ticks) {
			double[] out = new double[ticks];
			for (int i = 0; i < ticks; i++) {
				out[i] = vy;
				vy = (vy - GRAVITY) * 0.98;
			}
			return out;
		}

		@Test
		void vanillaJumpArcIsNeverFlagged() {
			assertTrue(run(new FlyCheck.State(), arc(0.42, 60)).isEmpty());
		}

		@Test
		void highJumpArcIsInvisibleToFly() {
			// Why movement.jump exists: after a too-high take-off the arc is normal physics again.
			assertTrue(run(new FlyCheck.State(), arc(0.9, 60)).isEmpty());
		}

		@Test
		void unloadedChunkFallIsIgnored() {
			double[] dys = new double[60];
			java.util.Arrays.fill(dys, FlyCheck.UNLOADED_CHUNK_DY);
			assertTrue(run(new FlyCheck.State(), dys).isEmpty());
		}

		@Test
		void hoverIsFlaggedThenHigh() {
			List<Flag> flags = run(new FlyCheck.State(), new double[60]);
			assertFalse(flags.isEmpty());
			assertEquals("hovering", flags.get(0).reason());
			assertEquals(Confidence.MEDIUM, flags.get(0).confidence());
			assertEquals(Confidence.HIGH, flags.get(flags.size() - 1).confidence());
		}

		@Test
		void untrustedResets() {
			FlyCheck.State s = new FlyCheck.State();
			for (int i = 0; i < 50; i++) {
				assertTrue(FlyCheck.onClientTick(s, 0, GRAVITY, true).isEmpty());
			}
		}
	}

	@Nested
	class Speed {
		@Test
		void boundAddsDecayAccelerationAndSprintJump() {
			assertEquals(0.3 * 0.546 + 0.1, SpeedCheck.bound(0.3, 0.546, 0.1, false), 1e-9);
			assertEquals(0.3 * 0.546 + 0.1 + SpeedCheck.SPRINT_JUMP_BOOST, SpeedCheck.bound(0.3, 0.546, 0.1, true), 1e-9);
		}

		@Test
		void steadyWalkingIsNotFlagged() {
			SpeedCheck.State s = new SpeedCheck.State();
			double decay = 0.6 * 0.91;
			double accel = 0.1 * 1.3 * 0.216 / Math.pow(0.6, 3);
			double v = 0;
			for (int i = 0; i < 100; i++) {
				v = v * decay + accel;
				assertTrue(SpeedCheck.onClientTick(s, v, decay, accel, false, false).isEmpty());
			}
		}

		@Test
		void sustainedSpeedHackIsFlagged() {
			SpeedCheck.State s = new SpeedCheck.State();
			double decay = 0.6 * 0.91;
			double accel = 0.13;
			List<Flag> flags = new ArrayList<>();
			for (int i = 0; i < 60; i++) {
				SpeedCheck.onClientTick(s, 0.5, decay, accel, false, false).ifPresent(flags::add);
			}
			assertFalse(flags.isEmpty());
			assertEquals(Confidence.HIGH, flags.get(flags.size() - 1).confidence());
		}

		@Test
		void untrustedTickResetsPrediction() {
			SpeedCheck.State s = new SpeedCheck.State();
			SpeedCheck.onClientTick(s, 0.1, 0.546, 0.1, false, false);
			// A teleport-sized jump on an untrusted tick, then normal movement: no flag.
			assertTrue(SpeedCheck.onClientTick(s, 5.0, 0.546, 0.1, false, true).isEmpty());
			assertTrue(SpeedCheck.onClientTick(s, 0.1, 0.546, 0.1, false, false).isEmpty());
			assertTrue(SpeedCheck.onClientTick(s, 0.15, 0.546, 0.1, false, false).isEmpty());
		}
	}

	@Nested
	class Timer {
		private static final long MS = 1_000_000L;

		private List<Flag> run(TimerCheck.State s, long[] gapsMs) {
			List<Flag> flags = new ArrayList<>();
			long t = 0;
			TimerCheck.onClientTick(s, t, 50, 500);
			for (long gap : gapsMs) {
				t += gap * MS;
				TimerCheck.onClientTick(s, t, 50, 500).ifPresent(flags::add);
			}
			return flags;
		}

		@Test
		void realTimeIsFine() {
			long[] gaps = new long[2000];
			java.util.Arrays.fill(gaps, 50);
			assertTrue(run(new TimerCheck.State(), gaps).isEmpty());
		}

		@Test
		void jitterIsFine() {
			long[] gaps = new long[2000];
			for (int i = 0; i < gaps.length; i++) {
				gaps[i] = i % 2 == 0 ? 10 : 90;
			}
			assertTrue(run(new TimerCheck.State(), gaps).isEmpty());
		}

		@Test
		void freezeThenCatchUpBurstIsFine() {
			// Fake lag / a lag spike: 1.5 s of nothing, then the 30 missed ticks at once, then normal.
			long[] gaps = new long[200];
			java.util.Arrays.fill(gaps, 50);
			gaps[50] = 1500;
			for (int i = 51; i < 80; i++) {
				gaps[i] = 0;
			}
			assertTrue(run(new TimerCheck.State(), gaps).isEmpty());
		}

		@Test
		void doubleSpeedIsFlagged() {
			long[] gaps = new long[200];
			java.util.Arrays.fill(gaps, 25);
			List<Flag> flags = run(new TimerCheck.State(), gaps);
			assertFalse(flags.isEmpty());
			assertEquals(Confidence.HIGH, flags.get(0).confidence());
		}

		@Test
		void savedUpLagCreditExpires() {
			// A long freeze must not allow running fast much later.
			long[] gaps = new long[400];
			java.util.Arrays.fill(gaps, 50);
			gaps[10] = 5000;
			for (int i = 100; i < 400; i++) {
				gaps[i] = 25;
			}
			assertFalse(run(new TimerCheck.State(), gaps).isEmpty());
		}
	}

	@Nested
	class GroundSpoof {
		@Test
		void requiredTicksGrowWithLatency() {
			assertEquals(GroundSpoofCheck.BASE_TICKS, GroundSpoofCheck.requiredTicks(0));
			assertEquals(GroundSpoofCheck.BASE_TICKS + 6, GroundSpoofCheck.requiredTicks(300));
			assertEquals(GroundSpoofCheck.BASE_TICKS + 20, GroundSpoofCheck.requiredTicks(-1));
		}

		@Test
		void claimingGroundInTheAirIsFlaggedAfterTheStreak() {
			GroundSpoofCheck.State s = new GroundSpoofCheck.State();
			int required = GroundSpoofCheck.requiredTicks(100);
			for (int i = 1; i < required; i++) {
				assertTrue(GroundSpoofCheck.onClientTick(s, true, false, false, 100, 70).isEmpty());
			}
			Optional<Flag> flag = GroundSpoofCheck.onClientTick(s, true, false, false, 100, 70);
			assertTrue(flag.isPresent());
			assertEquals(Confidence.HIGH, flag.get().confidence());
		}

		@Test
		void groundBelowOrExemptionBreaksTheStreak() {
			GroundSpoofCheck.State s = new GroundSpoofCheck.State();
			for (int i = 0; i < 200; i++) {
				boolean below = i % 5 == 0;
				boolean exempt = i % 7 == 0;
				assertTrue(GroundSpoofCheck.onClientTick(s, true, below, exempt, 0, 70).isEmpty());
			}
		}
	}
}
