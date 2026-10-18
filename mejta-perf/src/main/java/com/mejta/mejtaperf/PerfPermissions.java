package com.mejta.mejtaperf;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/** MejtaPerf's permission nodes. */
public final class PerfPermissions {
	public static final MejtaPermission VIEW = MejtaPermission.of("mejtaperf.view", PermissionLevel.MODERATORS)
			.describedAs("See TPS, MSPT, memory, dimensions, entities and Mejta metrics (/perf)");
	public static final MejtaPermission ALERTS = MejtaPermission.of("mejtaperf.alerts", PermissionLevel.MODERATORS)
			.describedAs("Receive lag alerts in chat");
	public static final MejtaPermission PROFILER = MejtaPermission.of("mejtaperf.profiler", PermissionLevel.ADMINS)
			.describedAs("Start and stop the profiler and save reports to the server's disk");

	public static final MejtaPermission[] ALL = {VIEW, ALERTS, PROFILER};

	private PerfPermissions() {
	}
}
