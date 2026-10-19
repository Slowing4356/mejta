package com.mejta.mejtaac.enforce;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.AcSettings.MitigationMode;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Decides what to do about a flag, separately from detection and alerting. A flag can only lead
 * to enforcement if its confidence is at least the configured minimum AND the check's enforceable
 * violation level (fed only by flags at that confidence or above, see {@code ViolationTracker})
 * reached the check's threshold, so one ambiguous signal never punishes anyone and low-confidence
 * flags never count towards a kick.
 *
 * <p>Mitigation is a separate decision: it undoes the action behind a single confident flag (a setback,
 * a cancelled hit or block action) instead of punishing the player, so it needs no violation level. It
 * never happens in {@code OFF} mode, below the minimum confidence, or unless {@code enforcement.mitigation}
 * is {@code ON}.
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

	/** Whether mitigation can happen at all with these settings (DRY_RUN and ENFORCE, mitigation ON). */
	public static boolean mitigationActive(EnforcementMode mode, MitigationMode mitigation) {
		return mitigation == MitigationMode.ON && mode != EnforcementMode.OFF;
	}

	/**
	 * Whether the action behind this flag is undone. Independent of violation levels and of kicks: a
	 * single flag at the minimum confidence is enough, in DRY_RUN as in ENFORCE. A flag that kicks
	 * ({@code decided == KICK}) needs no mitigation.
	 */
	public static boolean mitigates(EnforcementMode mode, MitigationMode mitigation, Confidence minConfidence, Flag flag,
			Action decided) {
		return mitigationActive(mode, mitigation) && flag.confidence().atLeast(minConfidence) && decided != Action.KICK;
	}
}
