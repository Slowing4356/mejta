package com.mejta.mejtaac.check.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects attacks on two different entities within one client tick (kill aura, multi aura).
 *
 * <p>Evidence: the vanilla client processes every queued attack click of a tick against the same
 * crosshair target, picked once per tick, so all attack packets between two
 * {@code ServerboundClientTickEndPacket}s name the same entity. Not timing-based, so latency cannot
 * cause a flag. (Spears hit several entities through a different packet, which is not counted.)
 */
public final class MultiTargetCheck {
	public static final String ID = "combat.multi_target";
	public static final CheckCategory CATEGORY = CheckCategory.COMBAT;

	public static final class State {
		int firstTarget = -1;
		int attacks;
	}

	private MultiTargetCheck() {
	}

	public static Optional<Flag> onAttack(State s, int entityId) {
		s.attacks++;
		if (s.firstTarget == -1) {
			s.firstTarget = entityId;
			return Optional.empty();
		}
		if (s.firstTarget == entityId) {
			return Optional.empty();
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("first_entity", s.firstTarget);
		m.put("other_entity", entityId);
		m.put("attacks_this_tick", s.attacks);
		return Optional.of(new Flag(ID, "two_targets_one_tick", Confidence.CERTAIN, 1.0, m));
	}

	public static void onClientTickEnd(State s) {
		s.firstTarget = -1;
		s.attacks = 0;
	}
}
