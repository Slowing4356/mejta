package com.mejta.mejtaac.check.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.check.movement.ElytraCheck.Motion;

class ElytraCheckTest {
	private static final double GRAVITY = 0.08;
	private static final double TOLERANCE = ElytraCheck.DEFAULT_TOLERANCE;

	/**
	 * The vanilla client while gliding, written out independently of the check: per tick, tiny velocity
	 * components are zeroed (LivingEntity.aiStep), the glide update is applied, then the player moves by
	 * the new velocity. Rotations are floats like the client's; the server receives the yaw wrapped.
	 */
	private static final class Glider {
		private static final float[] TABLE = new float[65536];

		static {
			for (int i = 0; i < TABLE.length; i++) {
				TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
			}
		}

		double vx;
		double vy;
		double vz;
		float yaw;
		float pitch;
		final List<double[]> ticks = new ArrayList<>(); // dx, dy, dz, wrapped yaw, pitch

		Glider(double vx, double vy, double vz) {
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
		}

		static float msin(float v) {
			return TABLE[(int) (v * 10430.378F) & 65535];
		}

		static float mcos(float v) {
			return TABLE[(int) (v * 10430.378F + 16384.0F) & 65535];
		}

		void tick() {
			if (Math.abs(vx) < 0.003) {
				vx = 0;
			}
			if (Math.abs(vy) < 0.003) {
				vy = 0;
			}
			if (Math.abs(vz) < 0.003) {
				vz = 0;
			}
			float p = pitch * (float) (Math.PI / 180.0);
			float yr = -yaw * (float) (Math.PI / 180.0);
			double lx = msin(yr) * mcos(p);
			double lz = mcos(yr) * mcos(p);
			double lookH = Math.sqrt(lx * lx + lz * lz);
			double h = Math.sqrt(vx * vx + vz * vz);
			double lift = Math.cos(p) * Math.cos(p);
			vy += GRAVITY * (-1.0 + lift * 0.75);
			if (vy < 0 && lookH > 0) {
				double c = vy * -0.1 * lift;
				vx += lx * c / lookH;
				vy += c;
				vz += lz * c / lookH;
			}
			if (p < 0 && lookH > 0) {
				double c = h * -msin(p) * 0.04;
				vx -= lx * c / lookH;
				vy += c * 3.2;
				vz -= lz * c / lookH;
			}
			if (lookH > 0) {
				vx += (lx / lookH * h - vx) * 0.1;
				vz += (lz / lookH * h - vz) * 0.1;
			}
			vx *= 0.99F;
			vy *= 0.98F;
			vz *= 0.99F;
			float wrapped = yaw % 360.0F;
			if (wrapped >= 180.0F) {
				wrapped -= 360.0F;
			}
			if (wrapped < -180.0F) {
				wrapped += 360.0F;
			}
			ticks.add(new double[] {vx, vy, vz, wrapped, pitch});
		}
	}

	private static List<Flag> judge(List<double[]> ticks) {
		ElytraCheck.State s = new ElytraCheck.State();
		List<Flag> flags = new ArrayList<>();
		for (double[] t : ticks) {
			ElytraCheck.onClientTick(s, new Motion(t[0], t[1], t[2]), (float) t[3], (float) t[4], GRAVITY, TOLERANCE, false)
					.ifPresent(flags::add);
		}
		return flags;
	}

	@Test
	void levelGlideSpiralIsNeverFlagged() {
		Glider g = new Glider(0, -0.1, 0.1);
		for (int i = 0; i < 400; i++) {
			g.yaw += 3.0F;
			g.tick();
		}
		assertTrue(judge(g.ticks).isEmpty());
	}

	@Test
	void diveThenPullUpThenLevelIsNeverFlagged() {
		Glider g = new Glider(0.5, -0.2, 0.5);
		g.yaw = 37.5F;
		for (int i = 0; i < 200; i++) {
			g.pitch = i < 40 ? 60 : i < 70 ? -45 : i < 90 ? -90 : i < 120 ? 90 : 0;
			g.tick();
		}
		double peakSpeed = g.ticks.stream().mapToDouble(t -> Math.hypot(Math.hypot(t[0], t[2]), t[1])).max().orElse(0);
		assertTrue(peakSpeed > 1.5, "the dive really built up speed: " + peakSpeed);
		assertTrue(g.ticks.stream().anyMatch(t -> t[1] > 0.05), "the pull-up really climbed");
		assertTrue(judge(g.ticks).isEmpty());
	}

	@Test
	void straightUpAndDownWithSpinningYawIsNeverFlagged() {
		// Looking (almost) straight up or down, vanilla steers by the sign of a rounding residue.
		Glider g = new Glider(1.2, -0.3, 0.4);
		for (int i = 0; i < 160; i++) {
			g.pitch = i % 40 < 20 ? 90 : -90;
			g.yaw += 17.0F;
			g.tick();
		}
		assertTrue(judge(g.ticks).isEmpty());
	}

	@Test
	void randomLegitimateFlightIsNeverFlagged() {
		for (long seed = 1; seed <= 30; seed++) {
			Random r = new Random(seed);
			Glider g = new Glider(r.nextGaussian(), -Math.abs(r.nextGaussian()) * 0.5, r.nextGaussian());
			g.yaw = r.nextFloat() * 720 - 360;
			g.pitch = r.nextFloat() * 180 - 90;
			for (int i = 0; i < 1500; i++) {
				g.yaw += (float) (r.nextGaussian() * 15);
				g.pitch = Math.max(-90, Math.min(90, g.pitch + (float) (r.nextGaussian() * 12)));
				if (r.nextInt(50) == 0) {
					g.pitch = r.nextBoolean() ? 90 : -90;
				}
				g.tick();
			}
			List<Flag> flags = judge(g.ticks);
			assertTrue(flags.isEmpty(), "seed " + seed + ": " + flags);
		}
	}

	@Test
	void afterAFireworkBoostGlidingIsJudgedAgainWithoutFlags() {
		Glider g = new Glider(0, -0.1, 0.1);
		ElytraCheck.State s = new ElytraCheck.State();
		List<Flag> flags = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			boolean boosting = i >= 50 && i < 80;
			if (boosting) {
				// FireworkRocketEntity.tick: v += look × 0.1 + (look × 1.5 − v) × 0.5 (look straight ahead, +z).
				g.vz += 0.1 + (1.5 - g.vz) * 0.5;
				g.vx += -g.vx * 0.5;
				g.vy += -g.vy * 0.5;
			}
			g.tick();
			double[] t = g.ticks.get(g.ticks.size() - 1);
			ElytraCheck.onClientTick(s, new Motion(t[0], t[1], t[2]), (float) t[3], (float) t[4], GRAVITY, TOLERANCE, boosting)
					.ifPresent(flags::add);
		}
		assertTrue(g.ticks.stream().anyMatch(t -> Math.hypot(t[0], t[2]) > 1.4), "the boost really accelerated");
		assertTrue(flags.isEmpty(), flags.toString());
	}

	/** Cheat: constant horizontal speed and constant height, whatever the rotation. */
	private static List<Flag> constantFlight(double speed, double dy, float pitch, int ticks) {
		ElytraCheck.State s = new ElytraCheck.State();
		List<Flag> flags = new ArrayList<>();
		for (int i = 0; i < ticks; i++) {
			ElytraCheck.onClientTick(s, new Motion(0, dy, speed), 0, pitch, GRAVITY, TOLERANCE, false).ifPresent(flags::add);
		}
		return flags;
	}

	@Test
	void hoveringWithoutFireworksIsFlaggedMediumOnly() {
		List<Flag> flags = constantFlight(1.5, 0.0, 0, 80);
		assertFalse(flags.isEmpty());
		assertEquals("hovering", flags.get(0).reason());
		assertTrue(flags.stream().allMatch(f -> f.confidence() == Confidence.MEDIUM && f.vlIncrease() == 0.5));
		int firstTick = (Integer) flags.get(0).measurements().get("ticks");
		assertTrue(firstTick <= 60, "flagged within 3 s: " + firstTick);
	}

	@Test
	void hoveringWhileLookingUpOrDownIsFlagged() {
		assertFalse(constantFlight(1.95, 0.0, -20, 80).isEmpty(), "looking up: climbing must cost speed");
		assertFalse(constantFlight(1.95, 0.0, 30, 80).isEmpty(), "looking down");
	}

	@Test
	void ascendingIsFlaggedQuickly() {
		List<Flag> flags = constantFlight(1.0, 0.3, 0, 30);
		assertFalse(flags.isEmpty());
		assertEquals("ascending", flags.get(0).reason());
	}

	@Test
	void boostingWithoutARocketIsFlagged() {
		Glider g = new Glider(0, -0.1, 0.6);
		for (int i = 0; i < 120; i++) {
			g.vz += 0.06; // a little extra forward speed each tick
			g.tick();
		}
		List<Flag> flags = judge(g.ticks);
		assertFalse(flags.isEmpty());
		assertEquals("too_fast", flags.get(0).reason());
	}

	@Test
	void untrustedTicksResetAndTheFirstTickOnlyEstablishes() {
		ElytraCheck.State s = new ElytraCheck.State();
		for (int i = 0; i < 100; i++) {
			assertTrue(ElytraCheck.onClientTick(s, new Motion(0, 0.5, 3), 0, 0, GRAVITY, TOLERANCE, true).isEmpty());
		}
		assertFalse(s.violating());
		// A reset followed by a single wild tick (e.g. the end of a boost) is never judged.
		assertTrue(ElytraCheck.onClientTick(s, new Motion(0, 0.9, 2.5), 0, 0, GRAVITY, TOLERANCE, false).isEmpty());
		assertFalse(s.violating());
	}

	@Test
	void flagsAreRateLimited() {
		List<Flag> flags = constantFlight(1.0, 0.5, 0, 200);
		assertTrue(flags.size() <= 200 / ElytraCheck.FLAG_INTERVAL_TICKS + 1);
	}

	@Test
	void lookVectorMatchesVanillaRounding() {
		Motion down = ElytraCheck.look(0, 90);
		assertEquals(-1.0, down.y(), 1e-6);
		assertTrue(Math.hypot(down.x(), down.z()) < ElytraCheck.STEEP_LOOK);
		Motion ahead = ElytraCheck.look(0, 0);
		assertEquals(1.0, ahead.z(), 1e-6);
		Motion east = ElytraCheck.look(-90, 0);
		assertEquals(1.0, east.x(), 1e-4);
	}
}
