package com.mejta.mejtaac.enforce;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Decides what to do about a flag, separately from detection and alerting. A flag can only lead
 * to enforcement if its confidence is at least the configured minimum AND the accumulated violation
 * level reached the check's threshold, so one ambiguous signal never punishes anyone.
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

	public static Action decide(EnforcementMode mode, Confidence minConfidence, Flag flag, double violationLevel, double threshold) {
		if (mode == EnforcementMode.OFF || !flag.confidence().atLeast(minConfidence) || violationLevel < threshold) {
			return Action.NONE;
		}
		return mode == EnforcementMode.ENFORCE ? Action.KICK : Action.WOULD_KICK;
	}
}
