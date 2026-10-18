package com.mejta.mejtaac.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.check.combat.HitboxCheck;
import com.mejta.mejtaac.check.combat.MultiTargetCheck;
import com.mejta.mejtaac.check.combat.ReachCheck;
import com.mejta.mejtaac.check.interaction.BlockReachCheck;
import com.mejta.mejtaac.check.interaction.FastBreakCheck;
import com.mejta.mejtaac.check.protocol.InvalidMoveCheck;
import com.mejta.mejtaac.check.protocol.MoveData;

/** Existing combat, interaction and protocol checks. */
class CombatAndInteractionChecksTest {
	private static final Vec3 EYE = new Vec3(0, 1.62, 0);

	/** A player-sized box whose nearest face is {@code distance} blocks in front (+z) of the eye. */
	private static AABB boxAt(double distance) {
		return new AABB(-0.3, 0, distance, 0.3, 1.8, distance + 0.6);
	}

	@Test
	void reachWithinRangeIsFine() {
		assertTrue(ReachCheck.evaluate(List.of(EYE), List.of(boxAt(2.9)), 3.0, 0.1, true, 50).isEmpty());
	}

	@Test
	void reachSlightlyBeyondIsMediumAndFarIsHigh() {
		Optional<Flag> medium = ReachCheck.evaluate(List.of(EYE), List.of(boxAt(3.3)), 3.0, 0.1, true, 50);
		assertEquals(Confidence.MEDIUM, medium.orElseThrow().confidence());
		Optional<Flag> high = ReachCheck.evaluate(List.of(EYE), List.of(boxAt(4.2)), 3.0, 0.1, true, 50);
		assertEquals(Confidence.HIGH, high.orElseThrow().confidence());
	}

	@Test
	void reachUsesTheClosestOfAllCandidatePositions() {
		assertTrue(ReachCheck.evaluate(List.of(EYE), List.of(boxAt(6), boxAt(2.5)), 3.0, 0.1, true, 50).isEmpty());
	}

	@Test
	void unsyncedReachIsNeverHigh() {
		assertEquals(Confidence.MEDIUM,
				ReachCheck.evaluate(List.of(EYE), List.of(boxAt(5)), 3.0, 0.1, false, -1).orElseThrow().confidence());
	}

	@Test
	void reachWindowCoversUpdateIntervalAndInterpolation() {
		assertEquals(100 - 3 - ReachCheck.INTERPOLATION_TICKS, ReachCheck.windowStart(100, 3));
		assertEquals(100 - 20 - ReachCheck.INTERPOLATION_TICKS, ReachCheck.windowStart(100, 400));
	}

	@Test
	void sweepJoinsConsecutivePositions() {
		List<AABB> swept = ReachCheck.sweep(List.of(boxAt(1), boxAt(2), boxAt(3)));
		assertEquals(2, swept.size());
		assertEquals(boxAt(1).minmax(boxAt(2)), swept.get(0));
	}

	@Test
	void hitboxLookingAtTargetIsFine() {
		HitboxCheck.Rotation ahead = new HitboxCheck.Rotation(0, 0); // yaw 0 looks towards +z
		assertTrue(HitboxCheck.evaluate(List.of(EYE), List.of(ahead), List.of(boxAt(2)), 4, true, 50).isEmpty());
	}

	@Test
	void hitboxLookingAwayIsHighWhenSynced() {
		HitboxCheck.Rotation behind = new HitboxCheck.Rotation(180, 0);
		assertEquals(Confidence.HIGH,
				HitboxCheck.evaluate(List.of(EYE), List.of(behind), List.of(boxAt(2)), 4, true, 50).orElseThrow().confidence());
		assertEquals(Confidence.MEDIUM,
				HitboxCheck.evaluate(List.of(EYE), List.of(behind), List.of(boxAt(2)), 4, false, -1).orElseThrow().confidence());
	}

	@Test
	void multiTargetFlagsTwoEntitiesInOneTick() {
		MultiTargetCheck.State s = new MultiTargetCheck.State();
		assertTrue(MultiTargetCheck.onAttack(s, 1).isEmpty());
		assertTrue(MultiTargetCheck.onAttack(s, 1).isEmpty());
		assertEquals(Confidence.CERTAIN, MultiTargetCheck.onAttack(s, 2).orElseThrow().confidence());
		MultiTargetCheck.onClientTickEnd(s);
		assertTrue(MultiTargetCheck.onAttack(s, 2).isEmpty());
	}

	@Test
	void blockReach() {
		AABB block = new AABB(0, 0, 4, 1, 1, 5);
		assertTrue(BlockReachCheck.evaluate(List.of(EYE), block, 4.5, 0.1, "start_destroy").isEmpty());
		AABB far = new AABB(0, 0, 6, 1, 1, 7);
		assertEquals(Confidence.HIGH, BlockReachCheck.evaluate(List.of(EYE), far, 4.5, 0.1, "use_on_block").orElseThrow().confidence());
	}

	@Test
	void fastBreak() {
		assertEquals(15, FastBreakCheck.expectedTicks(1.0 / 15));
		assertTrue(FastBreakCheck.onFinish(1.0 / 15, 13).isEmpty());
		assertEquals(Confidence.MEDIUM, FastBreakCheck.onFinish(1.0 / 15, 11).orElseThrow().confidence());
		assertEquals(Confidence.HIGH, FastBreakCheck.onFinish(1.0 / 15, 3).orElseThrow().confidence());
		assertTrue(FastBreakCheck.onFinish(1.0, 0).isEmpty());
	}

	@Test
	void invalidMove() {
		InvalidMoveCheck check = new InvalidMoveCheck();
		assertTrue(check.evaluate(MoveData.both(10, 64, 10, 45, 30)).isEmpty());
		assertEquals("non_finite_position", check.evaluate(MoveData.position(Double.NaN, 64, 0)).orElseThrow().reason());
		assertEquals("position_out_of_bounds", check.evaluate(MoveData.position(4.0E7, 64, 0)).orElseThrow().reason());
		assertEquals("non_finite_rotation", check.evaluate(MoveData.rotation(Float.POSITIVE_INFINITY, 0)).orElseThrow().reason());
		assertEquals("impossible_pitch", check.evaluate(MoveData.rotation(0, 91)).orElseThrow().reason());
	}
}
