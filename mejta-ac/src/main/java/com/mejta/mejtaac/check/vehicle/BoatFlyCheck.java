package com.mejta.mejtaac.check.vehicle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects boats (and rafts) that hover, rise or fall slower than gravity allows in the air ("boat
 * fly"). The player steering a boat simulates it on their own client and the server accepts the
 * positions it reports, so without this check a boat can fly freely.
 *
 * <p>Vanilla boat physics in the air ({@code AbstractBoat.floatBoat}, status {@code IN_AIR}): every
 * tick the vertical velocity loses the boat's gravity (0.04 by default) with <em>no</em> drag, and the
 * boat then moves by it. So each airborne tick's vertical movement is predictable from the previous
 * one: {@code dy = previousDy - gravity}. Only moving <em>up</em> relative to that is suspicious:
 * landing, ceilings and other collisions only ever make the movement smaller.
 *
 * <p>The caller resets the prediction (no flag possible) whenever it cannot be trusted: water, lava
 * or bubble columns in or just below the boat (buoyancy, snapping to the water surface, bubble
 * launches), ground or a collision on the predicted path (landing, slime bounces, sliding off a
 * ledge), cobwebs and similar blocks, pistons, other collidable entities touching the boat, the first
 * ticks after mounting, server corrections of the boat (rejected moves, teleports, knockback of the
 * boat) until the client has applied them, and dimension changes. The first airborne tick after a
 * reset only establishes the velocity, so falling off a ledge or being launched by a bubble column is
 * never a flag.
 *
 * <p>Latency: only the sequence of boat positions reported by the client is used, which latency and
 * lag spikes do not change; server corrections are matched to the client's ticks through tick sync.
 */
public final class BoatFlyCheck {
	public static final String ID = "vehicle.boat_fly";
	public static final CheckCategory CATEGORY = CheckCategory.VEHICLE;

	static final double TOLERANCE = 0.03;
	static final double FLAG_BUFFER = 0.5;
	/** Consecutive violating ticks after which a flag is HIGH confidence. */
	static final int HIGH_TICKS = 20;
	static final int FLAG_INTERVAL_TICKS = 10;

	public static final class State {
		boolean hasPrev;
		double prevDy;
		double buffer;
		int violatingTicks;
		int ticksSinceFlag = FLAG_INTERVAL_TICKS;

		public void reset() {
			hasPrev = false;
			violatingTicks = 0;
			buffer = Math.max(0, buffer - 0.05);
		}
	}

	private BoatFlyCheck() {
	}

	/** Movement predicted for this tick from the previous one, if known. */
	public static OptionalDouble expectedDy(State s, double gravity) {
		return s.hasPrev ? OptionalDouble.of(s.prevDy - gravity) : OptionalDouble.empty();
	}

	/**
	 * Accounts for one client tick of a boat the player controls.
	 *
	 * @param dy        vertical movement of the boat during this client tick
	 * @param gravity   the boat's gravity (0.04 by default)
	 * @param untrusted whether the prediction must be reset this tick (see class description)
	 */
	public static Optional<Flag> onClientTick(State s, double dy, double gravity, boolean untrusted) {
		s.ticksSinceFlag++;
		if (untrusted) {
			s.reset();
			return Optional.empty();
		}
		if (!s.hasPrev) {
			s.hasPrev = true;
			s.prevDy = dy;
			return Optional.empty();
		}
		double expected = s.prevDy - gravity;
		s.prevDy = dy;
		double excess = dy - expected;
		if (excess <= TOLERANCE) {
			s.violatingTicks = 0;
			s.buffer = Math.max(0, s.buffer - 0.05);
			return Optional.empty();
		}
		s.violatingTicks++;
		s.buffer += excess;
		if (s.buffer < FLAG_BUFFER || s.ticksSinceFlag < FLAG_INTERVAL_TICKS) {
			return Optional.empty();
		}
		s.buffer = 0;
		s.ticksSinceFlag = 0;
		Confidence confidence = s.violatingTicks >= HIGH_TICKS ? Confidence.HIGH : Confidence.MEDIUM;
		String reason = dy > 0.01 ? "ascending" : dy >= -0.01 ? "hovering" : "falling_too_slowly";
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("dy", Math.round(dy * 10000.0) / 10000.0);
		m.put("expected_dy", Math.round(expected * 10000.0) / 10000.0);
		m.put("ticks", s.violatingTicks);
		return Optional.of(new Flag(ID, reason, confidence, confidence == Confidence.HIGH ? 1.0 : 0.5, m));
	}
}
