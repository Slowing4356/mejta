package com.mejta.mejtaac;

import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.AcSettings.LogLevel;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Immutable snapshot of the configuration. The server thread reads one consistent snapshot per
 * packet; a change or reload (possibly on another thread) swaps in a new one atomically.
 */
public record AcConfig(
		boolean enabled,
		LogLevel logLevel,
		double decayPerMinute,
		boolean alertsEnabled,
		double alertsMinVl,
		int alertsCooldownSeconds,
		EnforcementMode enforcementMode,
		Confidence enforcementMinConfidence,
		String kickMessage,
		boolean bypassPermission,
		boolean recordsEnabled,
		boolean invalidMoveEnabled,
		double invalidMoveKickVl,
		boolean timerEnabled,
		double timerKickVl,
		int timerMaxAheadMs,
		boolean groundSpoofEnabled,
		double groundSpoofKickVl,
		boolean reachEnabled,
		double reachKickVl,
		double reachTolerance,
		boolean multiTargetEnabled,
		double multiTargetKickVl,
		boolean flyEnabled,
		double flyKickVl,
		boolean hitboxEnabled,
		double hitboxKickVl,
		boolean blockReachEnabled,
		double blockReachKickVl,
		double blockReachTolerance) {

	public static AcConfig from(ConfigHandle c) {
		return new AcConfig(
				c.get(AcSettings.ENABLED),
				c.get(AcSettings.LOG_LEVEL),
				c.get(AcSettings.DECAY_PER_MINUTE),
				c.get(AcSettings.ALERTS_ENABLED),
				c.get(AcSettings.ALERTS_MIN_VL),
				c.get(AcSettings.ALERTS_COOLDOWN),
				c.get(AcSettings.ENFORCEMENT_MODE),
				c.get(AcSettings.ENFORCEMENT_MIN_CONFIDENCE),
				c.get(AcSettings.KICK_MESSAGE),
				c.get(AcSettings.BYPASS_PERMISSION),
				c.get(AcSettings.RECORDS_ENABLED),
				c.get(AcSettings.INVALID_MOVE_ENABLED),
				c.get(AcSettings.INVALID_MOVE_KICK_VL),
				c.get(AcSettings.TIMER_ENABLED),
				c.get(AcSettings.TIMER_KICK_VL),
				c.get(AcSettings.TIMER_MAX_AHEAD_MS),
				c.get(AcSettings.GROUND_SPOOF_ENABLED),
				c.get(AcSettings.GROUND_SPOOF_KICK_VL),
				c.get(AcSettings.REACH_ENABLED),
				c.get(AcSettings.REACH_KICK_VL),
				c.get(AcSettings.REACH_TOLERANCE),
				c.get(AcSettings.MULTI_TARGET_ENABLED),
				c.get(AcSettings.MULTI_TARGET_KICK_VL),
				c.get(AcSettings.FLY_ENABLED),
				c.get(AcSettings.FLY_KICK_VL),
				c.get(AcSettings.HITBOX_ENABLED),
				c.get(AcSettings.HITBOX_KICK_VL),
				c.get(AcSettings.BLOCK_REACH_ENABLED),
				c.get(AcSettings.BLOCK_REACH_KICK_VL),
				c.get(AcSettings.BLOCK_REACH_TOLERANCE));
	}

	/** Whether any check needs per-tick client sync (pings and entity history). */
	public boolean needsSync() {
		return enabled && (reachEnabled || groundSpoofEnabled || hitboxEnabled || flyEnabled);
	}

	public boolean logs(LogLevel level) {
		return logLevel.ordinal() >= level.ordinal();
	}
}
