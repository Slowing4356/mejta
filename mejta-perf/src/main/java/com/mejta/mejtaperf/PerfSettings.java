package com.mejta.mejtaperf;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** MejtaPerf's settings, in {@code config/mejta/mejtaperf.json}. Keys are stable. */
public final class PerfSettings {
	public static final Setting<Boolean> ALERTS = Setting.bool("alerts.enabled", true)
			.describedAs("Publish a lag alert (channel mejtaperf.lag) when the server stays slow; staff see it in chat, "
					+ "and MejtaDiscord can forward it");
	public static final Setting<Integer> ALERT_MSPT = Setting.integer("alerts.mspt_threshold", 50, 10, 1000)
			.describedAs("Milliseconds per tick that count as lag. 50 ms is the most a tick can take at 20 TPS.");
	public static final Setting<Integer> ALERT_SUSTAIN = Setting.integer("alerts.sustain_seconds", 10, 1, 600)
			.describedAs("How long MSPT must stay at or above the threshold before an alert, and below 80% of it before the recovery");
	public static final Setting<Integer> ALERT_COOLDOWN = Setting.integer("alerts.cooldown_minutes", 10, 1, 1440)
			.describedAs("Minimum time between two lag alerts");
	public static final Setting<Boolean> ALERT_RECOVERY = Setting.bool("alerts.recovery", true)
			.describedAs("Also publish a message when the server has recovered from an alerted lag");
	public static final Setting<Boolean> ALERT_PROFILE = Setting.bool("alerts.auto_profile", false).experimental()
			.describedAs("Start a 30-second profile when a lag alert fires (unless one is running) and save its report");

	public static final Setting<Integer> PROFILER_INTERVAL = Setting.integer("profiler.interval_ms", 10, 2, 100)
			.describedAs("Milliseconds between two samples of the server thread. Lower is more precise and costs more.");
	public static final Setting<Integer> PROFILER_MAX = Setting.integer("profiler.max_seconds", 600, 10, 3600)
			.describedAs("A profile stops by itself after this long");

	public static final Setting<Integer> REPORTS_KEEP = Setting.integer("reports.keep", 20, 1, 500)
			.describedAs("Reports of each kind kept in mejta-data/mejtaperf/reports/; older ones are deleted");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ALERTS, ALERT_MSPT, ALERT_SUSTAIN, ALERT_COOLDOWN, ALERT_RECOVERY, ALERT_PROFILE, PROFILER_INTERVAL, PROFILER_MAX,
					REPORTS_KEEP)
			.build();

	private PerfSettings() {
	}
}
