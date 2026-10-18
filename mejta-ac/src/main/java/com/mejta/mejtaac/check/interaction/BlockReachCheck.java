package com.mejta.mejtaac.check.interaction;

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
 * Detects starting to break, or using/placing against, a block beyond the player's block
 * interaction range (4.5 in survival, 5 in creative, or whatever the attribute says).
 *
 * <p>Vanilla allows one extra block. The vanilla client picks blocks along its look ray up to the
 * range, so the distance from its eye to the block's box can never exceed it. Blocks do not move,
 * so no latency compensation is needed; the action is sent before that tick's movement, which the
 * server's current position matches. Standing and crouching eye heights are both tried.
 */
public final class BlockReachCheck {
	public static final String ID = "interaction.block_reach";
	public static final CheckCategory CATEGORY = CheckCategory.INTERACTION;

	static final double HIGH_EXCESS = 0.5;

	private BlockReachCheck() {
	}

	public static Optional<Flag> evaluate(List<Vec3> eyes, AABB block, double range, double tolerance, String action) {
		if (eyes.isEmpty()) {
			return Optional.empty();
		}
		double best = Double.MAX_VALUE;
		for (Vec3 eye : eyes) {
			best = Math.min(best, block.distanceToSqr(eye));
		}
		double distance = Math.sqrt(best);
		double excess = distance - range;
		if (excess <= tolerance) {
			return Optional.empty();
		}
		Confidence confidence = excess > HIGH_EXCESS ? Confidence.HIGH : Confidence.MEDIUM;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("action", action);
		m.put("distance", Math.round(distance * 1000.0) / 1000.0);
		m.put("max", range);
		m.put("excess", Math.round(excess * 1000.0) / 1000.0);
		return Optional.of(new Flag(ID, "too_far", confidence, confidence == Confidence.HIGH ? 1.0 : 0.5, m));
	}
}
