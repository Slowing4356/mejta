package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects elytra flight that ignores glide physics: flying level or upwards without losing speed
 * ("elytra fly"), gliding faster than physics allows, or boosting without a firework rocket.
 * {@code movement.fly} and {@code movement.speed} do not judge gliding players at all.
 *
 * <p>Vanilla glide physics ({@code LivingEntity.updateFallFlyingMovement}) changes the velocity once per
 * client tick, <em>before</em> the move, from the previous velocity and the player's rotation in that
 * tick: gravity reduced by lift ({@code cos²(pitch)}), falling speed converted into forward speed,
 * forward speed converted into climb when looking up, the horizontal velocity steered towards the look
 * direction, then drag (0.99 horizontally, 0.98 vertically). Without a boost, each tick's movement is
 * therefore predictable from the previous tick's movement and this tick's rotation, which the client
 * sends in the same movement packet. Only moving <em>higher</em> or <em>faster</em> than the prediction
 * counts: walls, ceilings and the ground only ever take movement away.
 *
 * <p>The caller resets the prediction (no flag possible) whenever it cannot be trusted: firework
 * rockets near the player, and until the client has seen the last one disappear (tick sync); server
 * velocity (knockback, explosions, wind charges) and abilities until the client confirms them; riptide,
 * levitation, slow falling, liquids, climbing, cobwebs and similar blocks, pistons and entities next to
 * the player, any block on or next to the path of the tick, the client claiming ground, a tick without a
 * position from the client (stuck at unloaded chunks), vehicles, unconfirmed teleports, and the
 * server not seeing the player glide. The first tick after a reset only establishes the velocity.
 *
 * <p>Latency: only the sequence of client positions and the rotations sent with them are used, which
 * latency does not change. Rockets and server velocity are matched to the client's ticks through tick sync.
 *
 * <p>Lenient by design: a tick only counts once it exceeds the prediction by {@code tolerance} plus 0.5 %
 * of the speed, the excess has to build up over many ticks, and flags are at most MEDIUM.
 */
public final class ElytraCheck {
	public static final String ID = "movement.elytra";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	/** Default absolute tolerance per tick (blocks); configurable. */
	public static final double DEFAULT_TOLERANCE = 0.01;
	/** Tolerance per tick, as a fraction of the predicted speed. */
	static final double RELATIVE_TOLERANCE = 0.005;
	static final double FLAG_BUFFER = 0.5;
	static final int FLAG_INTERVAL_TICKS = 10;
	/**
	 * Looking (almost) straight up or down, the horizontal part of the look vector is a rounding residue
	 * whose sign decides where vanilla steers. Below this size every possibility is allowed.
	 */
	static final double STEEP_LOOK = 1.0E-3;

	/** Movement or velocity in blocks per tick. */
	public record Motion(double x, double y, double z) {
		public double horizontal() {
			return Math.sqrt(x * x + z * z);
		}
	}

	/** The highest vertical and horizontal movement vanilla allows in a tick (they may come from different candidates). */
	public record Bound(double maxDy, double maxHorizontal) {
	}

	public static final class State {
		boolean hasPrev;
		Motion prev;
		double buffer;
		int violatingTicks;
		int ticksSinceFlag = FLAG_INTERVAL_TICKS;

		public void reset() {
			hasPrev = false;
			prev = null;
			violatingTicks = 0;
			buffer = Math.max(0, buffer - 0.05);
		}

		/** Whether an excess is building up or was just flagged (the position is not a safe setback target). */
		public boolean violating() {
			return violatingTicks > 0 || buffer > 0;
		}
	}

	// Vanilla's Mth.sin/cos lookup table: the look vector's rounding decides the steering direction when
	// looking straight up or down, so it is reproduced exactly.
	private static final float[] SIN = new float[65536];

	static {
		for (int i = 0; i < SIN.length; i++) {
			SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
		}
	}

	private ElytraCheck() {
	}

	static float sin(float value) {
		return SIN[(int) (value * 10430.378F) & 65535];
	}

	static float cos(float value) {
		return SIN[(int) (value * 10430.378F + 16384.0F) & 65535];
	}

	/** Vanilla's look vector ({@code Entity.calculateViewVector}). */
	public static Motion look(float yaw, float pitch) {
		float pitchRad = pitch * (float) (Math.PI / 180.0);
		float yawRad = -yaw * (float) (Math.PI / 180.0);
		float cosYaw = cos(yawRad);
		float sinYaw = sin(yawRad);
		float cosPitch = cos(pitchRad);
		float sinPitch = sin(pitchRad);
		return new Motion(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
	}

	/**
	 * One tick of vanilla glide physics: the velocity after {@code LivingEntity.updateFallFlyingMovement},
	 * which is also the movement of that tick when nothing is in the way.
	 *
	 * @param velocity the velocity before the tick (the previous tick's movement)
	 * @param look     the look vector of this tick
	 * @param pitch    the pitch of this tick, in degrees
	 * @param gravity  the player's gravity (attribute; 0.08 by default)
	 */
	public static Motion glide(Motion velocity, Motion look, float pitch, double gravity) {
		float pitchRad = pitch * (float) (Math.PI / 180.0);
		double lookHorizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
		double speed = velocity.horizontal();
		double lift = Math.cos(pitchRad);
		lift *= lift;
		double x = velocity.x();
		double y = velocity.y() + gravity * (-1.0 + lift * 0.75);
		double z = velocity.z();
		if (y < 0.0 && lookHorizontal > 0.0) {
			double convert = y * -0.1 * lift;
			x += look.x() * convert / lookHorizontal;
			y += convert;
			z += look.z() * convert / lookHorizontal;
		}
		if (pitchRad < 0.0F && lookHorizontal > 0.0) {
			double convert = speed * -sin(pitchRad) * 0.04;
			x += -look.x() * convert / lookHorizontal;
			y += convert * 3.2;
			z += -look.z() * convert / lookHorizontal;
		}
		if (lookHorizontal > 0.0) {
			x += (look.x() / lookHorizontal * speed - x) * 0.1;
			z += (look.z() / lookHorizontal * speed - z) * 0.1;
		}
		return new Motion(x * 0.99F, y * 0.98F, z * 0.99F);
	}

	/**
	 * The most permissive movement for this tick. Looking (almost) straight up or down, the steering
	 * direction depends on rounding, so the look's horizontal part is also tried reversed and as zero.
	 */
	public static Bound bound(Motion previous, float yaw, float pitch, double gravity) {
		Motion look = look(yaw, pitch);
		Motion predicted = glide(previous, look, pitch, gravity);
		double maxDy = predicted.y();
		double maxHorizontal = predicted.horizontal();
		if (Math.sqrt(look.x() * look.x() + look.z() * look.z()) < STEEP_LOOK) {
			for (Motion variant : new Motion[] {new Motion(-look.x(), look.y(), -look.z()), new Motion(0, look.y(), 0)}) {
				Motion other = glide(previous, variant, pitch, gravity);
				maxDy = Math.max(maxDy, other.y());
				maxHorizontal = Math.max(maxHorizontal, other.horizontal());
			}
		}
		return new Bound(maxDy, maxHorizontal);
	}

	/**
	 * Accounts for one client tick of a gliding player.
	 *
	 * @param moved     the player's movement during this client tick (server-accepted positions)
	 * @param yaw       the rotation the client sent with this tick's movement
	 * @param pitch     likewise, clamped to ±90 like the vanilla client does
	 * @param gravity   the player's gravity (attribute)
	 * @param tolerance blocks per tick allowed beyond the prediction (configurable)
	 * @param untrusted whether the prediction must be reset this tick (see class description)
	 */
	public static Optional<Flag> onClientTick(State s, Motion moved, float yaw, float pitch, double gravity, double tolerance,
			boolean untrusted) {
		s.ticksSinceFlag++;
		if (untrusted) {
			s.reset();
			return Optional.empty();
		}
		if (!s.hasPrev) {
			s.hasPrev = true;
			s.prev = moved;
			return Optional.empty();
		}
		Bound bound = bound(s.prev, yaw, pitch, gravity);
		s.prev = moved;
		double speed = moved.horizontal();
		double excessDy = moved.y() - bound.maxDy();
		double excessSpeed = speed - bound.maxHorizontal();
		double allowed = tolerance + RELATIVE_TOLERANCE * Math.hypot(bound.maxHorizontal(), bound.maxDy());
		double excess = Math.max(0, excessDy) + Math.max(0, excessSpeed);
		if (excess <= allowed) {
			s.violatingTicks = 0;
			s.buffer = Math.max(0, s.buffer - 0.05);
			return Optional.empty();
		}
		s.violatingTicks++;
		s.buffer += excess - allowed;
		if (s.buffer < FLAG_BUFFER || s.ticksSinceFlag < FLAG_INTERVAL_TICKS) {
			return Optional.empty();
		}
		s.buffer = 0;
		s.ticksSinceFlag = 0;
		String reason;
		if (excessSpeed > excessDy) {
			reason = "too_fast";
		} else {
			reason = moved.y() > 0.01 ? "ascending" : moved.y() >= -0.01 ? "hovering" : "falling_too_slowly";
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("dy", round(moved.y()));
		m.put("expected_dy", round(bound.maxDy()));
		m.put("speed", round(speed));
		m.put("expected_speed", round(bound.maxHorizontal()));
		m.put("pitch", Math.round(pitch * 10.0) / 10.0);
		m.put("ticks", s.violatingTicks);
		return Optional.of(new Flag(ID, reason, Confidence.MEDIUM, 0.5, m));
	}

	private static double round(double value) {
		return Math.round(value * 10000.0) / 10000.0;
	}
}
