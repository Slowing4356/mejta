package com.mejta.mejtaac.check.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects attacks on targets farther away than the held weapon allows.
 *
 * <p>The limit is vanilla's own: {@code AttackRange.effectiveMaxRange + hitboxMargin} of the main
 * hand item (so spears, custom weapons and creative reach are respected), measured like vanilla
 * from the attacker's eye to the target's bounding box. Vanilla itself allows 3 extra blocks.
 *
 * <p>Latency compensation: the distance is measured to the <em>nearest</em> place the target could
 * have been on the attacker's screen. That is every box the server recorded between
 * {@code lastAckTick - (updateInterval + interpolation + 2)} and {@code lastAckTick + 1}, where
 * {@code lastAckTick} is the newest tick the client confirmed (see {@code TickSync}). Consecutive
 * boxes are merged, because the client draws the target interpolated between them. The attacker's
 * eye is tried at both standing and crouching height. When the client has not confirmed any tick
 * recently, a full second of history is used and flags are at most MEDIUM.
 */
public final class ReachCheck {
	public static final String ID = "combat.reach";
	public static final CheckCategory CATEGORY = CheckCategory.COMBAT;

	/** Client interpolation steps (InterpolationHandler.DEFAULT_INTERPOLATION_STEPS) plus margin. */
	public static final int INTERPOLATION_TICKS = 3 + 2;
	/** Ticks of history used when the client's state is unknown. */
	public static final int UNSYNCED_WINDOW = 20;
	/** Excess beyond the limit, in blocks, at which a synced flag becomes HIGH confidence. */
	static final double HIGH_EXCESS = 0.5;

	private ReachCheck() {
	}

	/** Earliest tick the client may still have been showing, given its newest confirmed tick. */
	public static int windowStart(int lastAckTick, int updateInterval) {
		return lastAckTick - Math.min(updateInterval, 20) - INTERPOLATION_TICKS;
	}

	/** Bounding boxes covering the path between consecutive recorded positions. */
	public static List<AABB> sweep(List<AABB> boxes) {
		List<AABB> out = new ArrayList<>();
		if (boxes.size() == 1) {
			out.add(boxes.get(0));
		}
		for (int i = 1; i < boxes.size(); i++) {
			out.add(boxes.get(i - 1).minmax(boxes.get(i)));
		}
		return out;
	}

	/**
	 * @param eyes       candidate eye positions of the attacker
	 * @param candidates boxes the target may have had on the attacker's screen
	 * @param maxReach   effective max range + hitbox margin of the weapon
	 * @param tolerance  extra blocks allowed before any flag (configurable)
	 * @param synced     whether {@code candidates} came from a confirmed tick window
	 */
	public static Optional<Flag> evaluate(List<Vec3> eyes, List<AABB> candidates, double maxReach, double tolerance,
			boolean synced, int rttMs) {
		if (eyes.isEmpty() || candidates.isEmpty()) {
			return Optional.empty();
		}
		double best = Double.MAX_VALUE;
		for (Vec3 eye : eyes) {
			for (AABB box : candidates) {
				best = Math.min(best, box.distanceToSqr(eye));
			}
		}
		double distance = Math.sqrt(best);
		double excess = distance - maxReach;
		if (excess <= tolerance) {
			return Optional.empty();
		}
		Confidence confidence = synced && excess > HIGH_EXCESS ? Confidence.HIGH : Confidence.MEDIUM;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("distance", round(distance));
		m.put("max", round(maxReach));
		m.put("excess", round(excess));
		m.put("synced", synced);
		m.put("rtt_ms", rttMs);
		m.put("positions_checked", candidates.size());
		return Optional.of(new Flag(ID, "too_far", confidence, confidence == Confidence.HIGH ? 1.0 : 0.5, m));
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}
}
