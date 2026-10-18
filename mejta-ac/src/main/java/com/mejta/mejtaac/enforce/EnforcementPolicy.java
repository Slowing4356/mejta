package com.mejta.mejtaac.enforce;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Decides what to do about a flag, separately from detection and alerting. A flag can only lead
 * to enforcement if its confidence is at least the configured minimum AND the check's enforceable
 * violation level (fed only by flags at that confidence or above, see {@code ViolationTracker})
 * reached the check's threshold, so one ambiguous signal never punishes anyone and low-confidence
 * flags never count towards a kick.
 */
public final class EnforcementPolicy {
	public enum Action {
		NONE,
		/** DRY_RUN: would have kicked. Recorded and alerted, nothing happens to the player. */
		WOULD_KICK,
		KICK
	}

	private EnforcementPolicy() {
	}

	/**
	 * @param enforceableLevel the check's level from flags at {@code minConfidence} or above only,
	 *                         including this one
	 */
	public static Action decide(EnforcementMode mode, Confidence minConfidence, Flag flag, double enforceableLevel, double threshold) {
		if (mode == EnforcementMode.OFF || !flag.confidence().atLeast(minConfidence) || enforceableLevel < threshold) {
			return Action.NONE;
		}
		return mode == EnforcementMode.ENFORCE ? Action.KICK : Action.WOULD_KICK;
	}
}
