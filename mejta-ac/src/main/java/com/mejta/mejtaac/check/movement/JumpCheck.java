package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects jumps higher than vanilla allows ("high jump"), which {@code movement.fly} cannot see: the
 * rest of a too-high jump follows normal gravity, only the take-off is wrong.
 *
 * <p>Vanilla take-off: {@code jumpFromGround} sets the vertical velocity to
 * {@code max(jumpPower, current)}, where {@code jumpPower = JUMP_STRENGTH × blockJumpFactor +
 * jumpBoost}, and the client then moves by exactly that velocity in the same tick. So the first
 * airborne tick after standing on the ground can rise at most {@code jumpPower}. Block jump factors
 * (honey) only lower it, and ceilings only shorten the move.
 *
 * <p>The caller only reports take-offs it can judge: ground (server-side or claimed by the client)
 * at the end of the previous client tick, no ground now, and nothing that legitimately pushes a
 * player up. It resets nothing here; instead it passes the most permissive bound:
 * <ul>
 * <li>jump boost and jump strength as the highest value of the last seconds plus the player's
 * round trip, so an effect that just ran out on the server, but not yet on the client, is still
 * allowed (lag and fake lag cannot make a legitimate jump flag);</li>
 * <li>slime and bed bounces: up to the falling speed before landing;</li>
 * <li>step-ups never reach this check, because the player stands on the block afterwards.</li>
 * </ul>
 * Not judged at all (decided by the caller): knockback and other server velocity until applied,
 * vehicles and the first ticks after leaving one, liquids, climbing, levitation, flight, elytra,
 * riptide, cobwebs/honey/scaffolding/bubble columns/powder snow, pistons and collidable entities
 * (boats, shulkers, minecarts) next to the player, and unconfirmed teleports.
 *
 * <p>One too-high jump is MEDIUM: a single take-off is a single sample. It becomes HIGH when the
 * excess is clear ({@value #HIGH_EXCESS} blocks) and repeated ({@value #HIGH_STRIKES} jumps within
 * {@value #STRIKE_WINDOW_TICKS} client ticks).
 */
public final class JumpCheck {
	public static final String ID = "movement.jump";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	/** Rounding of positions sent by the client and float/double conversions in vanilla. */
	static final double TOLERANCE = 0.03;
	static final double HIGH_EXCESS = 0.1;
	static final int HIGH_STRIKES = 3;
	static final long STRIKE_WINDOW_TICKS = 600;

	public static final class State {
		int strikes;
		long lastStrikeTick = Long.MIN_VALUE;
	}

	private JumpCheck() {
	}

	/** Highest vertical movement in the take-off tick. */
	public static double maxTakeOff(double jumpPower, double bounce) {
		return Math.max(jumpPower, bounce);
	}

	/**
	 * Accounts for one take-off.
	 *
	 * @param dy         vertical movement in the first airborne client tick
	 * @param jumpPower  most permissive jump power (see class description)
	 * @param bounce     upward speed a slime or bed bounce could give, 0 if none
	 * @param clientTick the player's client tick counter, for the strike window
	 */
	public static Optional<Flag> onTakeOff(State s, double dy, double jumpPower, double bounce, long clientTick) {
		double max = maxTakeOff(jumpPower, bounce);
		double excess = dy - max;
		if (excess <= TOLERANCE) {
			return Optional.empty();
		}
		if (s.lastStrikeTick == Long.MIN_VALUE || clientTick - s.lastStrikeTick > STRIKE_WINDOW_TICKS) {
			s.strikes = 0;
		}
		s.strikes++;
		s.lastStrikeTick = clientTick;
		boolean high = excess > HIGH_EXCESS && s.strikes >= HIGH_STRIKES;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("dy", round(dy));
		m.put("max", round(max));
		m.put("jump_power", round(jumpPower));
		m.put("bounce", round(bounce));
		m.put("strikes", s.strikes);
		return Optional.of(new Flag(ID, "too_high", high ? Confidence.HIGH : Confidence.MEDIUM, high ? 1.0 : 0.5, m));
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}
}
