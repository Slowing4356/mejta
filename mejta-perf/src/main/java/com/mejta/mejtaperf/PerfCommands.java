package com.mejta.mejtaperf;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtaperf.metrics.MetricRates;
import com.mejta.mejtaperf.tick.TickStats;

/**
 * {@code /perf} (also {@code /mejtaadmin mejtaperf perf}):
 * <ul>
 * <li>{@code /perf} or {@code /perf status}: TPS, MSPT, world, memory, CPU</li>
 * <li>{@code /perf entities}: entity types and crowded chunks</li>
 * <li>{@code /perf metrics [addon]}: Mejta metrics</li>
 * <li>{@code /perf profiler start [seconds] | stop | status}</li>
 * <li>{@code /perf report}: saves a full report</li>
 * </ul>
 */
final class PerfCommands {
	private PerfCommands() {
	}

	static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaPerf.ID, "perf", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(PerfPermissions.VIEW))
				.executes(PerfCommands::status)
				.then(Commands.literal("status").executes(PerfCommands::status))
				.then(Commands.literal("entities").executes(PerfCommands::entities))
				.then(Commands.literal("metrics")
						.executes(ctx -> metrics(ctx, ""))
						.then(Commands.argument("addon", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										MejtaCoreAPI.getAddons().stream().map(a -> a.id()).toList(), builder))
								.executes(ctx -> metrics(ctx, StringArgumentType.getString(ctx, "addon")))))
				.then(Commands.literal("profiler")
						.requires(MejtaCoreAPI.requires(PerfPermissions.PROFILER))
						.then(Commands.literal("start")
								.executes(ctx -> startProfiler(ctx, -1))
								.then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
										.executes(ctx -> startProfiler(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
						.then(Commands.literal("stop").executes(PerfCommands::stopProfiler))
						.then(Commands.literal("status").executes(PerfCommands::profilerStatus)))
				.then(Commands.literal("report")
						.requires(MejtaCoreAPI.requires(PerfPermissions.PROFILER))
						.executes(PerfCommands::report)));
	}

	// ---------------------------------------------------------------- status

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PerfMonitor monitor = MejtaPerf.monitor();
		long now = System.nanoTime();
		TickStats ticks = monitor.ticks();
		header(source, "Server performance");

		MutableComponent tps = Component.literal(" TPS ").withStyle(ChatFormatting.GRAY);
		for (int seconds : new int[] {5, 60, 300, 900}) {
			TickStats.Window w = ticks.window(now, seconds);
			tps.append(Component.literal(Fmt.duration(seconds).replace(" ", "") + " ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(Fmt.tps(w.tps()) + "  ").withStyle(tpsColor(w.tps())));
		}
		source.sendSystemMessage(tps);

		TickStats.Window minute = ticks.window(now, 60);
		source.sendSystemMessage(Component.literal(" MSPT (1 min) ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("mean " + Fmt.ms(minute.meanMs())).withStyle(msptColor(minute.meanMs())))
				.append(Component.literal(", median " + Fmt.ms(minute.medianMs()) + ", p95 " + Fmt.ms(minute.p95Ms()) + ", max ")
						.withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Fmt.ms(minute.maxMs()) + " ms").withStyle(msptColor(minute.maxMs()))));
		if (monitor.alarm().lagging()) {
			source.sendSystemMessage(Component.literal(Fmt.f(" Lagging for %s, peak %s ms", Fmt.duration(monitor.alarm().lagNanos(now) / 1_000_000_000L),
					Fmt.ms(monitor.alarm().peakMs()))).withStyle(ChatFormatting.RED));
		}

		ServerStats.Snapshot stats = ServerStats.collect(source.getServer(), monitor.levels().msPerTick());
		line(source, Fmt.f(" %d players, %s chunks, %s entities", stats.players(), Fmt.count(stats.chunks()), Fmt.count(stats.entities())));
		for (ServerStats.Level level : stats.levels()) {
			line(source, Fmt.f("  %s: %s ms/tick, %s chunks, %s entities", shortId(level.id()), Fmt.ms(level.msPerTick()), Fmt.count(level.chunks()),
					Fmt.count(level.entities())));
		}

		JvmStats.Snapshot jvm = JvmStats.snapshot();
		double[] gc = monitor.jvm().gcLastMinute();
		line(source, Fmt.f(" Memory %s of %s (max %s); GC %.0f collections, %.0f ms in %s", Fmt.bytes(jvm.heapUsed()), Fmt.bytes(jvm.heapCommitted()),
				Fmt.bytes(jvm.heapMax()), gc[0], gc[1], Fmt.duration(Math.round(gc[2]))));
		line(source, Fmt.f(" CPU process %s, machine %s, %d cores, %d threads; uptime %s", Fmt.percent(jvm.processCpu()),
				Fmt.percent(jvm.systemCpu()), jvm.processors(), jvm.threads(), Fmt.duration(monitor.uptimeSeconds())));
		// Who started a running profile is shown only to those who may run the profiler themselves.
		line(source, " " + MejtaPerf.profiler().status(MejtaCoreAPI.hasPermission(source, PerfPermissions.PROFILER)));
		return 1;
	}

	private static int entities(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerStats.Snapshot stats = ServerStats.collect(source.getServer(), MejtaPerf.monitor().levels().msPerTick());
		header(source, Fmt.f("Entities: %s in %d dimensions", Fmt.count(stats.entities()), stats.levels().size()));
		stats.entityTypes().entrySet().stream().limit(10)
				.forEach(e -> line(source, Fmt.f(" %8s  %s", Fmt.count(e.getValue()), e.getKey())));
		if (!stats.crowds().isEmpty()) {
			line(source, " Most crowded chunks:");
			for (ServerStats.Crowd c : stats.crowds().subList(0, Math.min(5, stats.crowds().size()))) {
				line(source, Fmt.f("  %s in %s near %d %d, mostly %s", Fmt.count(c.entities()), shortId(c.level()), c.chunkX() * 16 + 8,
						c.chunkZ() * 16 + 8, c.topType()));
			}
		}
		line(source, " Possible contributors, not a diagnosis: the profiler shows where time goes.");
		return stats.entities();
	}

	private static int metrics(CommandContext<CommandSourceStack> ctx, String addon) {
		CommandSourceStack source = ctx.getSource();
		MetricRates rates = MejtaPerf.monitor().metrics();
		List<MetricRates.Rate> list = rates.rates().stream().filter(r -> addon.isEmpty() || r.addonId().equals(addon)).toList();
		header(source, "Mejta metrics, last " + Fmt.duration(Math.round(rates.windowSeconds())) + (addon.isEmpty() ? "" : " (" + addon + ")"));
		if (list.isEmpty()) {
			line(source, " Nothing reported" + (addon.isEmpty() ? " yet." : " by " + addon + "."));
			return 0;
		}
		int shown = 0;
		for (MetricRates.Rate r : list) {
			if (shown++ >= 20) {
				line(source, Fmt.f(" ... %d more: /perf report lists all of them.", list.size() - 20));
				break;
			}
			String text = switch (r.kind()) {
				case TIMER -> Fmt.f(" %s: %s ms/tick, %.1f calls/s, avg %s µs, peak %s ms", r.id(), Fmt.ms(r.msPerTick()), r.perSecond(),
						Double.isNaN(r.meanMicros()) ? "-" : Fmt.f("%.1f", r.meanMicros()), Fmt.ms(r.peakMs()));
				case COUNTER -> Fmt.f(" %s: %.1f/s (total %s)", r.id(), r.perSecond(), Fmt.count(r.value()));
				case GAUGE -> Fmt.f(" %s: %s", r.id(), Fmt.count(r.value()));
			};
			line(source, text);
		}
		return list.size();
	}

	// ---------------------------------------------------------------- profiler and reports

	private static int startProfiler(CommandContext<CommandSourceStack> ctx, int requested) {
		CommandSourceStack source = ctx.getSource();
		MejtaPerf.Snapshot s = MejtaPerf.snapshot();
		int seconds = requested < 0 ? s.profilerMax() : Math.min(requested, s.profilerMax());
		String reason = "started by " + source.getTextName();
		String problem = MejtaPerf.profiler().start(source.getServer(), seconds, s.profilerInterval(), reason, result -> MejtaPerf.announce(result));
		audit(ctx, "profiler.start", problem == null ? AuditOutcome.SUCCESS : AuditOutcome.FAILED, Map.of("seconds", seconds));
		if (problem != null) {
			source.sendFailure(Component.literal(problem));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(Fmt.f("Profiling the server thread for up to %s, one sample every %d ms. ",
				Fmt.duration(seconds), s.profilerInterval())).append(Component.literal("/perf profiler stop").withStyle(ChatFormatting.YELLOW))
				.append(" ends it early; the report is saved either way."), true);
		return 1;
	}

	private static int stopProfiler(CommandContext<CommandSourceStack> ctx) {
		boolean stopped = MejtaPerf.profiler().stop();
		audit(ctx, "profiler.stop", stopped ? AuditOutcome.SUCCESS : AuditOutcome.FAILED, Map.of());
		if (!stopped) {
			ctx.getSource().sendFailure(Component.literal("No profile is running."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Stopping the profiler; the report follows in a moment."), true);
		return 1;
	}

	private static int profilerStatus(CommandContext<CommandSourceStack> ctx) {
		line(ctx.getSource(), MejtaPerf.profiler().status());
		return MejtaPerf.profiler().running() ? 1 : 0;
	}

	private static int report(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		String text = PerfReports.build(source.getServer(), MejtaPerf.monitor(), MejtaPerf.profiler().status(false));
		Instant now = Instant.now();
		// The answer goes back through the server thread, and only while that server is still running.
		boolean queued = MejtaPerf.io().submit(() -> {
			Path file;
			try {
				file = MejtaPerf.reports().write("report", now, text);
			} catch (Exception e) {
				MejtaPerf.log().error("Could not save the performance report", e);
				MejtaPerf.runOnServer(source.getServer(), () -> source.sendFailure(Component.literal("Could not save the report: "
						+ e.getClass().getSimpleName() + ". See the server log.")));
				return;
			}
			MejtaPerf.runOnServer(source.getServer(), () -> source.sendSuccess(() -> Component.literal("Report saved to "
					+ PerfReports.relative(file) + "."), false));
		});
		audit(ctx, "report.write", queued ? AuditOutcome.SUCCESS : AuditOutcome.FAILED, Map.of());
		if (!queued) {
			source.sendFailure(Component.literal("MejtaPerf is busy writing other reports; try again shortly."));
			return 0;
		}
		return 1;
	}

	private static void audit(CommandContext<CommandSourceStack> ctx, String action, AuditOutcome outcome, Map<String, Object> details) {
		AuditEntry.Builder entry = AuditEntry.builder(MejtaPerf.ID, action).actor(MejtaCoreAPI.actor(ctx.getSource())).outcome(outcome);
		details.forEach(entry::detail);
		MejtaCoreAPI.audit().record(entry.build());
	}

	// ---------------------------------------------------------------- formatting

	private static void header(CommandSourceStack source, String title) {
		source.sendSystemMessage(Component.literal("MejtaPerf: " + title).withStyle(ChatFormatting.GOLD));
	}

	private static void line(CommandSourceStack source, String text) {
		source.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	/** {@code minecraft:the_nether} to {@code the_nether}; other namespaces stay. */
	static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	static ChatFormatting tpsColor(double tps) {
		return Double.isNaN(tps) ? ChatFormatting.GRAY : tps >= 19 ? ChatFormatting.GREEN : tps >= 15 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	static ChatFormatting msptColor(double ms) {
		return Double.isNaN(ms) ? ChatFormatting.GRAY : ms < 40 ? ChatFormatting.GREEN : ms < 50 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}
}
