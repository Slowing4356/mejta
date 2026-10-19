package com.mejta.mejtaac;

import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/**
 * MejtaAC configuration, stored by MejtaCore in {@code config/mejta/mejtaac.json}. Keys are stable
 * IDs: renaming one requires bumping the spec version and adding a migration.
 *
 * <p>Every setting applies immediately on change or {@code /mejtaadmin config reload}, except the
 * {@code records.*} size limits, which apply after a restart.
 */
public final class AcSettings {
	public enum LogLevel {
		/** Only enforcement actions. */
		QUIET,
		/** Flags (rate-limited per player and check) and enforcement. */
		NORMAL,
		/** Like NORMAL, plus measurements and latency. */
		VERBOSE,
		/** Every flag without rate limiting, plus exemptions. For investigating false positives. */
		DEBUG
	}

	public enum EnforcementMode {
		/** Never act. */
		OFF,
		/** Record and alert what would have happened, but do not act. */
		DRY_RUN,
		/** Act (kick) when thresholds are reached. */
		ENFORCE
	}

	public enum MitigationMode {
		/** Never undo anything; flags are only recorded, alerted and counted towards kicks. */
		OFF,
		/**
		 * Undo the offending action of every flag at {@code enforcement.min_confidence} or above (in DRY_RUN and
		 * ENFORCE): set the player back, cancel the hit or cancel the block action.
		 */
		ON
	}

	// ---------------------------------------------------------------- general
	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
			.describedAs("Master switch. When off, no check runs and existing violation levels are kept");
	public static final Setting<LogLevel> LOG_LEVEL = Setting.enumeration("log.level", LogLevel.NORMAL)
			.describedAs("Server log detail: QUIET, NORMAL, VERBOSE (with measurements) or DEBUG (every flag)");

	// ---------------------------------------------------------------- violations
	public static final Setting<Double> DECAY_PER_MINUTE = Setting.decimal("violations.decay_per_minute", 2.0, 0.0, 600.0)
			.describedAs("Violation level removed per minute, per check. 0 = levels only reset on disconnect");

	// ---------------------------------------------------------------- alerts
	public static final Setting<Boolean> ALERTS_ENABLED = Setting.bool("alerts.enabled", true)
			.describedAs("Publish alerts (in-game staff chat, and external providers if installed and configured)");
	public static final Setting<Double> ALERTS_MIN_VL = Setting.decimal("alerts.min_vl", 1.0, 0.0, 10_000.0)
			.describedAs("Only alert once a player's violation level for a check reaches this value");
	public static final Setting<Integer> ALERTS_COOLDOWN = Setting.integer("alerts.cooldown_seconds", 5, 0, 3600)
			.describedAs("Minimum seconds between alerts for the same player and check");
	public static final Setting<Boolean> ALERTS_ENFORCEMENT = Setting.bool("alerts.enforcement", true)
			.describedAs("Publish every kick and would-be kick (staff chat, notification file log, Discord if installed), even when alerts.enabled is off");

	// ---------------------------------------------------------------- enforcement
	public static final Setting<EnforcementMode> ENFORCEMENT_MODE = Setting.enumeration("enforcement.mode", EnforcementMode.DRY_RUN)
			.describedAs("OFF, DRY_RUN (log and alert only) or ENFORCE (kick). Starts in DRY_RUN so you can review flags first");
	public static final Setting<Confidence> ENFORCEMENT_MIN_CONFIDENCE = Setting.enumeration("enforcement.min_confidence", Confidence.HIGH)
			.describedAs("Flags below this confidence never lead to enforcement and never count towards a kick_vl threshold");
	public static final Setting<MitigationMode> ENFORCEMENT_MITIGATION = Setting.enumeration("enforcement.mitigation", MitigationMode.OFF)
			.describedAs("[Experimental] ON: undo the action behind every flag at min_confidence or above (set back, cancel the hit or block action), in DRY_RUN and ENFORCE. Independent of kicks");
	public static final Setting<String> KICK_MESSAGE = Setting.string("enforcement.kick_message", "Disconnected by anti-cheat.")
			.validatedBy(text -> !text.isBlank() && text.length() <= 256)
			.describedAs("Message shown to kicked players (1-256 characters)");

	// ---------------------------------------------------------------- exemptions
	public static final Setting<Boolean> BYPASS_PERMISSION = Setting.bool("exemptions.bypass_permission", true)
			.describedAs("Exempt players who have the mejtaac.bypass permission (OWNERS level by default)");

	// ---------------------------------------------------------------- records
	public static final Setting<Boolean> RECORDS_ENABLED = Setting.bool("records.enabled", true)
			.describedAs("Write violation and enforcement records to mejta-data/mejtaac/");
	public static final Setting<Integer> RECORDS_MAX_FILE_MB = Setting.integer("records.max_file_mb", 5, 1, 256)
			.describedAs("Rotate a record file at this size in MiB (applies after restart)");
	public static final Setting<Integer> RECORDS_MAX_FILES = Setting.integer("records.max_files", 5, 1, 100)
			.describedAs("Record files kept per log, including the current one (applies after restart)");
	public static final Setting<Integer> RECORDS_RETENTION_DAYS = Setting.integer("records.retention_days", 30, 0, 3650)
			.describedAs("Delete rotated record files older than this; 0 = keep until max_files (applies after restart)");

	// ---------------------------------------------------------------- check: protocol.invalid_move
	public static final Setting<Boolean> INVALID_MOVE_ENABLED = Setting.bool("checks.protocol.invalid_move.enabled", true)
			.describedAs("Flag movement packets with values the vanilla client can never send (NaN, out of bounds, pitch > 90)");
	public static final Setting<Double> INVALID_MOVE_KICK_VL = Setting.decimal("checks.protocol.invalid_move.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (each invalid packet adds 1)");

	// ---------------------------------------------------------------- check: movement.timer
	public static final Setting<Boolean> TIMER_ENABLED = Setting.bool("checks.movement.timer.enabled", true)
			.describedAs("[Experimental] Flag clients that run more game ticks than real time allows (timer/speed-up)");
	public static final Setting<Double> TIMER_KICK_VL = Setting.decimal("checks.movement.timer.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (each flag adds 1)");
	public static final Setting<Integer> TIMER_MAX_AHEAD_MS = Setting.integer("checks.movement.timer.max_ahead_ms", 500, 200, 5000)
			.describedAs("How far ahead of real time (ms, beyond jitter and lag allowances) a client may get before a flag");

	// ---------------------------------------------------------------- check: movement.ground_spoof
	public static final Setting<Boolean> GROUND_SPOOF_ENABLED = Setting.bool("checks.movement.ground_spoof.enabled", true)
			.describedAs("[Experimental] Flag clients that claim to stand on the ground with nothing below them (also NoFall)");
	public static final Setting<Double> GROUND_SPOOF_KICK_VL = Setting.decimal("checks.movement.ground_spoof.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (each flag adds 1)");

	// ---------------------------------------------------------------- check: combat.reach
	public static final Setting<Boolean> REACH_ENABLED = Setting.bool("checks.combat.reach.enabled", true)
			.describedAs("[Experimental] Flag attacks beyond the weapon's range, compensating for each player's latency");
	public static final Setting<Double> REACH_KICK_VL = Setting.decimal("checks.combat.reach.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");
	public static final Setting<Double> REACH_TOLERANCE = Setting.decimal("checks.combat.reach.tolerance", 0.1, 0.0, 3.0)
			.describedAs("Extra blocks allowed beyond the weapon's range before any flag");

	// ---------------------------------------------------------------- check: combat.multi_target
	public static final Setting<Boolean> MULTI_TARGET_ENABLED = Setting.bool("checks.combat.multi_target.enabled", true)
			.describedAs("Flag attacks on two different entities within one client tick (impossible for the vanilla client)");
	public static final Setting<Double> MULTI_TARGET_KICK_VL = Setting.decimal("checks.combat.multi_target.kick_vl", 3.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (each flag adds 1)");

	// ---------------------------------------------------------------- check: movement.fly
	public static final Setting<Boolean> FLY_ENABLED = Setting.bool("checks.movement.fly.enabled", true)
			.describedAs("[Experimental] Flag hovering, flying up or falling slower than gravity allows");
	public static final Setting<Double> FLY_KICK_VL = Setting.decimal("checks.movement.fly.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: combat.hitbox
	public static final Setting<Boolean> HITBOX_ENABLED = Setting.bool("checks.combat.hitbox.enabled", true)
			.describedAs("[Experimental] Flag attacks on entities the player was not looking at (latency-compensated)");
	public static final Setting<Double> HITBOX_KICK_VL = Setting.decimal("checks.combat.hitbox.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: interaction.block_reach
	public static final Setting<Boolean> BLOCK_REACH_ENABLED = Setting.bool("checks.interaction.block_reach.enabled", true)
			.describedAs("[Experimental] Flag breaking or using blocks beyond the block interaction range");
	public static final Setting<Double> BLOCK_REACH_KICK_VL = Setting.decimal("checks.interaction.block_reach.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");
	public static final Setting<Double> BLOCK_REACH_TOLERANCE = Setting.decimal("checks.interaction.block_reach.tolerance", 0.1, 0.0, 3.0)
			.describedAs("Extra blocks allowed beyond the block interaction range before any flag");

	// ---------------------------------------------------------------- check: movement.speed
	public static final Setting<Boolean> SPEED_ENABLED = Setting.bool("checks.movement.speed.enabled", true)
			.describedAs("[Experimental] Flag horizontal movement faster than vanilla physics allows");
	public static final Setting<Double> SPEED_KICK_VL = Setting.decimal("checks.movement.speed.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: interaction.fast_break
	public static final Setting<Boolean> FAST_BREAK_ENABLED = Setting.bool("checks.interaction.fast_break.enabled", true)
			.describedAs("[Experimental] Flag blocks broken faster than tool, enchantments and effects allow");
	public static final Setting<Double> FAST_BREAK_KICK_VL = Setting.decimal("checks.interaction.fast_break.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: movement.jump
	public static final Setting<Boolean> JUMP_ENABLED = Setting.bool("checks.movement.jump.enabled", true)
			.describedAs("[Experimental] Flag jumps higher than jump strength and Jump Boost allow (high jump)");
	public static final Setting<Double> JUMP_KICK_VL = Setting.decimal("checks.movement.jump.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: vehicle.boat_fly
	public static final Setting<Boolean> BOAT_FLY_ENABLED = Setting.bool("checks.vehicle.boat_fly.enabled", true)
			.describedAs("[Experimental] Flag boats that hover, rise or fall slower than gravity allows (boat fly)");
	public static final Setting<Double> BOAT_FLY_KICK_VL = Setting.decimal("checks.vehicle.boat_fly.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (HIGH flags add 1; MEDIUM 0.5, counted only if min_confidence allows)");

	// ---------------------------------------------------------------- check: movement.elytra
	public static final Setting<Boolean> ELYTRA_ENABLED = Setting.bool("checks.movement.elytra.enabled", true)
			.describedAs("[Experimental] Flag elytra flight that ignores glide physics without a firework boost (elytra fly, glide speed)");
	public static final Setting<Double> ELYTRA_KICK_VL = Setting.decimal("checks.movement.elytra.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (flags are MEDIUM and add 0.5, counted only if min_confidence allows)");
	public static final Setting<Double> ELYTRA_TOLERANCE = Setting.decimal("checks.movement.elytra.tolerance", 0.01, 0.0, 0.5)
			.describedAs("Blocks per tick allowed beyond the glide prediction (plus 0.5 % of the speed) before a tick counts as suspicious");

	// ---------------------------------------------------------------- check: vehicle.horse
	public static final Setting<Boolean> HORSE_ENABLED = Setting.bool("checks.vehicle.horse.enabled", true)
			.describedAs("[Experimental] Flag horses, donkeys, mules and camels that move faster or jump higher than their own attributes allow");
	public static final Setting<Double> HORSE_KICK_VL = Setting.decimal("checks.vehicle.horse.kick_vl", 5.0, 1.0, 10_000.0)
			.describedAs("Violation level at which enforcement kicks (flags are MEDIUM and add 0.5, counted only if min_confidence allows)");
	public static final Setting<Double> HORSE_SPEED_TOLERANCE = Setting.decimal("checks.vehicle.horse.speed_tolerance", 0.06, 0.0, 1.0)
			.describedAs("Blocks per tick a mount may move beyond its speed limit (entity pushing, rounding) before a tick counts as suspicious");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ENABLED, LOG_LEVEL,
					DECAY_PER_MINUTE,
					ALERTS_ENABLED, ALERTS_MIN_VL, ALERTS_COOLDOWN, ALERTS_ENFORCEMENT,
					ENFORCEMENT_MODE, ENFORCEMENT_MIN_CONFIDENCE, ENFORCEMENT_MITIGATION, KICK_MESSAGE,
					BYPASS_PERMISSION,
					RECORDS_ENABLED, RECORDS_MAX_FILE_MB, RECORDS_MAX_FILES, RECORDS_RETENTION_DAYS,
					INVALID_MOVE_ENABLED, INVALID_MOVE_KICK_VL,
					TIMER_ENABLED, TIMER_KICK_VL, TIMER_MAX_AHEAD_MS,
					GROUND_SPOOF_ENABLED, GROUND_SPOOF_KICK_VL,
					REACH_ENABLED, REACH_KICK_VL, REACH_TOLERANCE,
					MULTI_TARGET_ENABLED, MULTI_TARGET_KICK_VL,
					FLY_ENABLED, FLY_KICK_VL,
					HITBOX_ENABLED, HITBOX_KICK_VL,
					BLOCK_REACH_ENABLED, BLOCK_REACH_KICK_VL, BLOCK_REACH_TOLERANCE,
					SPEED_ENABLED, SPEED_KICK_VL,
					FAST_BREAK_ENABLED, FAST_BREAK_KICK_VL,
					JUMP_ENABLED, JUMP_KICK_VL,
					BOAT_FLY_ENABLED, BOAT_FLY_KICK_VL,
					ELYTRA_ENABLED, ELYTRA_KICK_VL, ELYTRA_TOLERANCE,
					HORSE_ENABLED, HORSE_KICK_VL, HORSE_SPEED_TOLERANCE)
			.build();

	private AcSettings() {
	}
}
