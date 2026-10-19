package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects horizontal movement faster than vanilla physics allows (speed, strafe and bunny-hop
 * cheats).
 *
 * <p>Vanilla horizontal movement per tick is {@code previous * decay + acceleration}: on the ground
 * the decay is {@code blockFriction * 0.91} and the acceleration {@code movementSpeed * 0.216 /
 * blockFriction^3}; in the air 0.91 and 0.02 (0.026 sprinting); a sprint-jump adds 0.2 once. The
 * caller computes an upper bound from the player's real movement-speed attribute (sprinting, speed
 * effects, soul speed...) and the blocks underneath, always choosing the most permissive
 * possibility. Collisions, sneaking, items in use, soul sand and similar only make players slower.
 *
 * <p>Untrusted ticks (liquids, vehicles, elytra, riptide, flight, teleports, server-applied velocity
 * until the client confirms it) reset the prediction. Latency does not change the sequence of client
 * positions, so it needs no special handling. A tolerance absorbs entity pushing.
 */
public final class SpeedCheck {
	public static final String ID = "movement.speed";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	static final double TOLERANCE = 0.06;
	static final double SPRINT_JUMP_BOOST = 0.2;
	static final double FLAG_BUFFER = 0.5;
	static final int HIGH_TICKS = 20;
	static final int FLAG_INTERVAL_TICKS = 10;
	/** How far above the head a ceiling may cut a jump short enough that the player stays on the ground. */
	public static final double CEILING_REACH = 0.2;

	public static final class State {
		boolean hasPrev;
		double prevSpeed;
		double buffer;
		int violatingTicks;
		int ticksSinceFlag = FLAG_INTERVAL_TICKS;

		public void reset() {
			hasPrev = false;
			violatingTicks = 0;
			buffer = Math.max(0, buffer - 0.05);
		}

		/** Whether an excess is building up or was just flagged (the position is not a safe setback target). */
		public boolean violating() {
			return violatingTicks > 0 || buffer > 0;
		}
	}

	private SpeedCheck() {
	}

	/**
	 * Whether this tick may contain a sprint-jump's boost: a take-off (ground before, air now), or a
	 * sprinting player who jumped into a ceiling just above the head (vanilla still adds the boost,
	 * but the jump is cut short so the player still touches the ground).
	 */
	public static boolean sprintJumpPossible(boolean groundBefore, boolean groundNow, boolean sprinting, boolean ceilingAbove) {
		return groundBefore && (!groundNow || sprinting && ceilingAbove);
	}

	/** Highest horizontal movement vanilla allows this tick. */
	public static double bound(double prevSpeed, double decay, double acceleration, boolean tookOff) {
		return prevSpeed * decay + acceleration + (tookOff ? SPRINT_JUMP_BOOST : 0);
	}

	/**
	 * @param speed     horizontal distance moved this client tick
	 * @param decay     most permissive decay of the previous tick's movement
	 * @param accel     most permissive acceleration this tick
	 * @param tookOff   whether the player left the ground this tick (sprint-jump boost possible)
	 * @param untrusted whether the prediction must be reset this tick
	 */
	public static Optional<Flag> onClientTick(State s, double speed, double decay, double accel, boolean tookOff, boolean untrusted) {
		s.ticksSinceFlag++;
		if (untrusted) {
			s.reset();
			return Optional.empty();
		}
		if (!s.hasPrev) {
			s.hasPrev = true;
			s.prevSpeed = speed;
			return Optional.empty();
		}
		double limit = bound(s.prevSpeed, decay, accel, tookOff);
		s.prevSpeed = speed;
		double excess = speed - limit;
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
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("speed", Math.round(speed * 1000.0) / 1000.0);
		m.put("limit", Math.round(limit * 1000.0) / 1000.0);
		m.put("ticks", s.violatingTicks);
		return Optional.of(new Flag(ID, "too_fast", confidence, confidence == Confidence.HIGH ? 1.0 : 0.5, m));
	}
}
