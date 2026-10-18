package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects vertical movement in the air that ignores gravity: hovering, flying upwards, or falling
 * slower than physics allows (glide).
 *
 * <p>Evidence: the vertical distance a client moved per client tick. In the air, vanilla does
 * {@code vy = (vy - gravity) * 0.98} after every move, so each tick's movement is predictable from
 * the previous one. Only moving <em>up</em> relative to that prediction is suspicious: ceilings,
 * landing and other collisions only ever make the movement smaller.
 *
 * <p>The caller resets the prediction (no flag possible) whenever it cannot be trusted: the client
 * claiming to be on the ground (it may be standing on a block it has not yet seen disappear; false
 * claims are {@code movement.ground_spoof}'s job), ground or a collision on the predicted path, liquids, climbing, cobwebs and similar blocks, slow falling,
 * levitation, elytra, riptide, vehicles, creative flight, unconfirmed teleports, and until the client
 * confirms it applied any velocity the server sent (knockback, explosions, wind charges). The first
 * airborne tick after a reset only establishes the velocity (jumps, bounces).
 *
 * <p>Latency: the check only uses the sequence of client positions, which latency does not change;
 * velocity from the server is handled through tick sync. A client whose chunk below is not loaded
 * falls at a constant 0.098 per tick; that exact pattern is not counted.
 */
public final class FlyCheck {
	public static final String ID = "movement.fly";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	static final double TOLERANCE = 0.03;
	static final double FLAG_BUFFER = 0.5;
	/** Consecutive violating ticks after which a flag is HIGH confidence. */
	static final int HIGH_TICKS = 20;
	static final int FLAG_INTERVAL_TICKS = 10;
	static final double UNLOADED_CHUNK_DY = -0.098;

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

	private FlyCheck() {
	}

	/** Movement predicted for this tick from the previous one, if known. */
	public static OptionalDouble expectedDy(State s, double gravity) {
		return s.hasPrev ? OptionalDouble.of((s.prevDy - gravity) * 0.98) : OptionalDouble.empty();
	}

	/**
	 * Accounts for one client tick in the air.
	 *
	 * @param dy      vertical movement during this client tick (server-accepted positions)
	 * @param gravity the player's current gravity (attribute; 0.08 by default)
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
		double expected = (s.prevDy - gravity) * 0.98;
		s.prevDy = dy;
		if (Math.abs(dy - UNLOADED_CHUNK_DY) < 0.002) {
			return Optional.empty();
		}
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
