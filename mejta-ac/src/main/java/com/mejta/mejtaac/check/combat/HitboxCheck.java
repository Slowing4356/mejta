package com.mejta.mejtaac.check.combat;

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
 * Detects attacks on entities the player was not looking at (kill auras without rotations,
 * "through the back" hits).
 *
 * <p>The vanilla client only attacks what its crosshair ray hits. Within one client tick it picks
 * the target, sends the attack, and only then sends that tick's movement packet with the rotation
 * it used. So an attack is judged at the end of its client tick, against both the rotation the
 * server had when the attack arrived and the newest rotation of that tick. The target positions are
 * the same latency-compensated boxes as {@link ReachCheck}; the eye is tried at standing and crouching
 * height.
 *
 * <p>A ray that misses every box by more than {@value #MEDIUM_INFLATE} blocks is MEDIUM; one that also
 * misses with {@value #HIGH_INFLATE} blocks of slack is HIGH (when the client's tick is confirmed).
 */
public final class HitboxCheck {
	public static final String ID = "combat.hitbox";
	public static final CheckCategory CATEGORY = CheckCategory.COMBAT;

	static final double MEDIUM_INFLATE = 0.1;
	static final double HIGH_INFLATE = 0.4;

	/** Yaw and pitch in degrees, as in movement packets. */
	public record Rotation(float yaw, float pitch) {
	}

	private HitboxCheck() {
	}

	/**
	 * @param eyes       candidate eye positions of the attacker
	 * @param rotations  candidate rotations the client may have used
	 * @param candidates boxes the target may have had on the attacker's screen (already including
	 *                   the weapon's hitbox margin and the target's pick radius)
	 * @param rayLength  how far the ray is traced (attack range plus margin)
	 */
	public static Optional<Flag> evaluate(List<Vec3> eyes, List<Rotation> rotations, List<AABB> candidates, double rayLength,
			boolean synced, int rttMs) {
		if (eyes.isEmpty() || rotations.isEmpty() || candidates.isEmpty()) {
			return Optional.empty();
		}
		if (hits(eyes, rotations, candidates, rayLength, MEDIUM_INFLATE)) {
			return Optional.empty();
		}
		boolean high = synced && !hits(eyes, rotations, candidates, rayLength, HIGH_INFLATE);
		Confidence confidence = high ? Confidence.HIGH : Confidence.MEDIUM;
		Map<String, Object> m = new LinkedHashMap<>();
		Rotation last = rotations.get(rotations.size() - 1);
		m.put("yaw", last.yaw());
		m.put("pitch", last.pitch());
		m.put("angle_off", Math.round(smallestAngle(eyes, rotations, candidates) * 10.0) / 10.0);
		m.put("synced", synced);
		m.put("rtt_ms", rttMs);
		return Optional.of(new Flag(ID, "not_looking_at_target", confidence, high ? 1.0 : 0.5, m));
	}

	static boolean hits(List<Vec3> eyes, List<Rotation> rotations, List<AABB> candidates, double rayLength, double inflate) {
		for (Vec3 eye : eyes) {
			for (Rotation rotation : rotations) {
				Vec3 end = eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(rayLength));
				for (AABB box : candidates) {
					AABB grown = box.inflate(inflate);
					if (grown.contains(eye) || grown.clip(eye, end).isPresent()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Smallest angle in degrees between a look direction and a target box centre, for review. */
	static double smallestAngle(List<Vec3> eyes, List<Rotation> rotations, List<AABB> candidates) {
		double best = 180;
		for (Vec3 eye : eyes) {
			for (Rotation rotation : rotations) {
				Vec3 look = Vec3.directionFromRotation(rotation.pitch(), rotation.yaw());
				for (AABB box : candidates) {
					Vec3 to = box.getCenter().subtract(eye).normalize();
					double cos = Math.max(-1, Math.min(1, look.dot(to)));
					best = Math.min(best, Math.toDegrees(Math.acos(cos)));
				}
			}
		}
		return best;
	}
}
