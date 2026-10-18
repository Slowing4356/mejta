package com.mejta.mejtaac.check.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.Check;
import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Flags movement packets containing values an unmodified client can never send. Every reason is
 * deterministic: no timing, latency or physics is involved, so lag cannot cause a false positive.
 *
 * <ul>
 * <li>{@code non_finite_position}: NaN or infinite coordinates. Vanilla disconnects on NaN (without
 * logging who or why) and silently clamps infinity; this check records both.</li>
 * <li>{@code position_out_of_bounds}: |x| or |z| above 30,000,000 or |y| above 20,000,000.
 * Vanilla silently clamps these.</li>
 * <li>{@code non_finite_rotation}: NaN or infinite yaw/pitch. Vanilla disconnects without logging.</li>
 * <li>{@code impossible_pitch}: |pitch| above 90 degrees. The vanilla client clamps pitch to
 * [-90, 90] before sending; "derp"/spin modules and some kill auras send more.</li>
 * </ul>
 *
 * Yaw is deliberately not range-checked: the vanilla client sends unwrapped yaw that grows without
 * bound as the player turns, so any finite yaw is legitimate.
 */
public final class InvalidMoveCheck implements Check<MoveData> {
	public static final String ID = "protocol.invalid_move";

	/** Same limits vanilla clamps to in {@code ServerGamePacketListenerImpl}. */
	static final double MAX_HORIZONTAL = 3.0E7;
	static final double MAX_VERTICAL = 2.0E7;
	/** Tolerance for float rounding; the vanilla client never exceeds exactly 90. */
	static final float MAX_PITCH = 90.0F + 1.0E-3F;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public CheckCategory category() {
		return CheckCategory.PROTOCOL;
	}

	@Override
	public Optional<Flag> evaluate(MoveData move) {
		if (move.hasPos()) {
			if (!Double.isFinite(move.x()) || !Double.isFinite(move.y()) || !Double.isFinite(move.z())) {
				return flag("non_finite_position", position(move));
			}
			if (Math.abs(move.x()) > MAX_HORIZONTAL || Math.abs(move.z()) > MAX_HORIZONTAL || Math.abs(move.y()) > MAX_VERTICAL) {
				return flag("position_out_of_bounds", position(move));
			}
		}
		if (move.hasRot()) {
			if (!Float.isFinite(move.yRot()) || !Float.isFinite(move.xRot())) {
				return flag("non_finite_rotation", rotation(move));
			}
			if (Math.abs(move.xRot()) > MAX_PITCH) {
				return flag("impossible_pitch", rotation(move));
			}
		}
		return Optional.empty();
	}

	private static Optional<Flag> flag(String reason, Map<String, Object> measurements) {
		return Optional.of(new Flag(ID, reason, Confidence.CERTAIN, 1.0, measurements));
	}

	private static Map<String, Object> position(MoveData move) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", move.x());
		m.put("y", move.y());
		m.put("z", move.z());
		return m;
	}

	private static Map<String, Object> rotation(MoveData move) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("yaw", move.yRot());
		m.put("pitch", move.xRot());
		return m;
	}
}
