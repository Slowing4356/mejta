package com.mejta.mejtaac.check.vehicle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects horse-like mounts (horses, donkeys, mules, skeleton and zombie horses, camels) that move
 * faster or jump higher than their own attributes allow, or hover and fly ("horse fly", "horse speed",
 * "high jump"). The player riding a mount simulates it on their own client and the server accepts the
 * positions it reports ({@code ServerboundMoveVehiclePacket}), so without this check a mount can go
 * anywhere at any speed.
 *
 * <p>Vanilla physics of a ridden mount ({@code LivingEntity.travel} with the mount's own attributes):
 * <ul>
 * <li><b>Horizontal:</b> like a player, {@code previous × decay + acceleration}, where acceleration is
 * {@code movementSpeed × 0.216 / friction³} on the ground and {@code movementSpeed × 0.1} in the air, and
 * the decay {@code friction × 0.91} on the ground and 0.91 in the air. A jump adds at most 0.4 forward;
 * a camel's dash adds up to {@code 22.2222 × movementSpeed}, and a sprinting camel gets 0.1 more speed.</li>
 * <li><b>Take-off:</b> the jump sets the vertical speed to at most {@code jumpStrength + jumpBoost}
 * (a full jump bar), {@code 1.4285 ×} that for a camel's dash, and the mount moves by it in that tick.</li>
 * <li><b>In the air:</b> {@code dy = (previous dy − gravity) × 0.98}, as for players. Only moving
 * <em>up</em> relative to that counts: landing and ceilings only take movement away.</li>
 * </ul>
 * The caller passes the most permissive values: the highest speed and jump power of the last seconds
 * plus twice the round trip (an effect that just ran out on the server may still apply on the client),
 * the slipperiest block for the decay and the least slippery one for the acceleration.
 *
 * <p>The caller resets the prediction (no flag possible) whenever it cannot be trusted: liquids in or
 * just below the mount, cobwebs and similar blocks, pistons, other collidable entities touching it,
 * a leash, slime and beds under it (bounces), levitation or slow falling on the mount, the first ticks
 * after mounting, server corrections of the mount until the client has applied them (tick sync), and
 * teleports or dimension changes. Ground (also a claim by the client while the server just saw support
 * there) and collisions on the predicted path reset the vertical prediction.
 *
 * <p>Latency: only the sequence of mount positions reported by the client is used, which latency does
 * not change. Flags are at most MEDIUM.
 */
public final class HorseCheck {
	public static final String ID = "vehicle.horse";
	public static final CheckCategory CATEGORY = CheckCategory.VEHICLE;

	/** Default speed tolerance per tick (blocks); configurable. */
	public static final double DEFAULT_SPEED_TOLERANCE = 0.06;
	static final double VERTICAL_TOLERANCE = 0.03;
	static final double TAKE_OFF_TOLERANCE = 0.03;
	static final double FLAG_BUFFER = 0.5;
	static final int FLAG_INTERVAL_TICKS = 10;
	static final double UNLOADED_CHUNK_DY = -0.098;
	/** Forward boost of a full horse jump ({@code AbstractHorse.executeRidersJump}). */
	static final double JUMP_FORWARD_BOOST = 0.4;
	/** Camel dash ({@code Camel.executeRidersJump}): forward and vertical factors, and the sprint bonus. */
	static final double CAMEL_DASH_FORWARD = 22.2222;
	static final double CAMEL_DASH_UP = 1.4285;
	static final double CAMEL_SPRINT_SPEED = 0.1;
	/** Air acceleration of a ridden mount is {@code speed × 0.1}; never assume less than a sprinting player's. */
	static final double MIN_AIR_ACCELERATION = 0.026;

	/** The highest value of the recent past, kept for a while after it drops (effects ending on the client later). */
	public static final class Allowance {
		double max;
		long untilNanos = Long.MIN_VALUE;

		public double update(double current, long nowNanos, long keepNanos) {
			if (current >= max || nowNanos > untilNanos) {
				max = current;
				untilNanos = nowNanos + keepNanos;
			}
			return max;
		}
	}

	public static final class State {
		boolean hasPrevDy;
		double prevDy;
		double verticalBuffer;
		int verticalTicks;
		boolean hasPrevSpeed;
		double prevSpeed;
		double speedBuffer;
		int speedTicks;
		int ticksSinceFlag = FLAG_INTERVAL_TICKS;
		public final Allowance speed = new Allowance();
		public final Allowance jump = new Allowance();

		public void reset() {
			hasPrevDy = false;
			hasPrevSpeed = false;
			verticalTicks = 0;
			speedTicks = 0;
			verticalBuffer = Math.max(0, verticalBuffer - 0.05);
			speedBuffer = Math.max(0, speedBuffer - 0.05);
		}
	}

	/**
	 * One client tick of the mount.
	 *
	 * @param dy           vertical movement of the mount this tick
	 * @param speed        horizontal movement of the mount this tick
	 * @param groundBefore ground under the mount at the end of the previous tick
	 * @param groundNow    ground under the mount now (server-side, or claimed while support was just seen)
	 * @param pathBlocked  a block on the predicted vertical path (landing, ceiling): no vertical prediction
	 * @param maxTakeOff   most permissive take-off speed ({@link #maxTakeOff})
	 * @param gravity      the mount's gravity (attribute; 0.08 by default)
	 * @param decay        most permissive decay of the previous tick's horizontal movement
	 * @param acceleration most permissive horizontal acceleration this tick ({@link #acceleration})
	 * @param boost        horizontal boost a jump could add this tick, 0 if no jump is possible
	 */
	public record Tick(double dy, double speed, boolean groundBefore, boolean groundNow, boolean pathBlocked, double maxTakeOff,
			double gravity, double decay, double acceleration, double boost) {
	}

	private HorseCheck() {
	}

	/** Movement speed a ridden mount uses ({@code getRiddenSpeed}). */
	public static double riddenSpeed(double movementSpeed, boolean camel) {
		return camel ? movementSpeed + CAMEL_SPRINT_SPEED : movementSpeed;
	}

	/** Highest vertical movement in the take-off tick: a full jump, or a camel's dash. */
	public static double maxTakeOff(double jumpStrength, double jumpBoost, boolean camel) {
		double power = jumpStrength + jumpBoost;
		return camel ? CAMEL_DASH_UP * power : power;
	}

	/** Highest horizontal boost of a jump: 0.4 forward, or a camel's dash. */
	public static double jumpBoost(double movementSpeed, boolean camel) {
		return camel ? Math.max(JUMP_FORWARD_BOOST, CAMEL_DASH_FORWARD * movementSpeed) : JUMP_FORWARD_BOOST;
	}

	/**
	 * Most permissive horizontal acceleration.
	 *
	 * @param groundPossible whether the mount may have been on the ground at the start of the tick
	 * @param minFriction    the least slippery block under the mount (0.6 for most blocks)
	 */
	public static double acceleration(double riddenSpeed, boolean groundPossible, double minFriction) {
		double air = Math.max(riddenSpeed * 0.1, MIN_AIR_ACCELERATION);
		return groundPossible ? Math.max(air, riddenSpeed * 0.216 / (minFriction * minFriction * minFriction)) : air;
	}

	/** Vertical movement predicted for this tick from the previous one. */
	public static double expectedDy(double previousDy, double gravity) {
		return (previousDy - gravity) * 0.98;
	}

	/** Vertical movement predicted for the next tick in the air, if known. */
	public static OptionalDouble expectedDy(State s, double gravity) {
		return s.hasPrevDy ? OptionalDouble.of(expectedDy(s.prevDy, gravity)) : OptionalDouble.empty();
	}

	/**
	 * @param speedTolerance blocks per tick allowed beyond the speed limit (configurable)
	 * @param untrusted      whether the prediction must be reset this tick (see class description)
	 */
	public static Optional<Flag> onClientTick(State s, Tick t, double speedTolerance, boolean untrusted) {
		s.ticksSinceFlag++;
		if (untrusted) {
			s.reset();
			return Optional.empty();
		}
		Flag flag = null;

		// Take-off: one sample, judged on its own (like movement.jump).
		boolean tookOff = t.groundBefore() && !t.groundNow() && t.dy() > 0;
		if (tookOff && t.dy() - t.maxTakeOff() > TAKE_OFF_TOLERANCE) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("dy", round(t.dy()));
			m.put("max", round(t.maxTakeOff()));
			flag = new Flag(ID, "too_high", Confidence.MEDIUM, 0.5, m);
		}

		// In the air: gravity.
		if (t.groundNow() || t.pathBlocked()) {
			s.hasPrevDy = false;
			s.verticalTicks = 0;
		} else if (!s.hasPrevDy) {
			s.hasPrevDy = true; // the first airborne tick (a take-off or a ledge) only sets the velocity
			s.prevDy = t.dy();
		} else {
			double expected = expectedDy(s.prevDy, t.gravity());
			s.prevDy = t.dy();
			double excess = t.dy() - expected;
			if (Math.abs(t.dy() - UNLOADED_CHUNK_DY) < 0.002 || excess <= VERTICAL_TOLERANCE) {
				s.verticalTicks = 0;
				s.verticalBuffer = Math.max(0, s.verticalBuffer - 0.05);
			} else {
				s.verticalTicks++;
				s.verticalBuffer += excess;
				if (flag == null && s.verticalBuffer >= FLAG_BUFFER && s.ticksSinceFlag >= FLAG_INTERVAL_TICKS) {
					s.verticalBuffer = 0;
					String reason = t.dy() > 0.01 ? "ascending" : t.dy() >= -0.01 ? "hovering" : "falling_too_slowly";
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("dy", round(t.dy()));
					m.put("expected_dy", round(expected));
					m.put("ticks", s.verticalTicks);
					flag = new Flag(ID, reason, Confidence.MEDIUM, 0.5, m);
				}
			}
		}

		// Horizontal: decay and acceleration.
		if (!s.hasPrevSpeed) {
			s.hasPrevSpeed = true;
			s.prevSpeed = t.speed();
		} else {
			double limit = s.prevSpeed * t.decay() + t.acceleration() + t.boost();
			s.prevSpeed = t.speed();
			double excess = t.speed() - limit;
			if (excess <= speedTolerance) {
				s.speedTicks = 0;
				s.speedBuffer = Math.max(0, s.speedBuffer - 0.05);
			} else {
				s.speedTicks++;
				s.speedBuffer += excess;
				if (flag == null && s.speedBuffer >= FLAG_BUFFER && s.ticksSinceFlag >= FLAG_INTERVAL_TICKS) {
					s.speedBuffer = 0;
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("speed", round(t.speed()));
					m.put("limit", round(limit));
					m.put("ticks", s.speedTicks);
					flag = new Flag(ID, "too_fast", Confidence.MEDIUM, 0.5, m);
				}
			}
		}
		if (flag != null) {
			s.ticksSinceFlag = 0;
		}
		return Optional.ofNullable(flag);
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}
}
