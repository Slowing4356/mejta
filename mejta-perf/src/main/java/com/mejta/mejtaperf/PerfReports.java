package com.mejta.mejtaperf;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtaperf.metrics.MetricRates;
import com.mejta.mejtaperf.report.ReportFiles;
import com.mejta.mejtaperf.tick.TickStats;

/**
 * The full performance report ({@code /perf report}) and where reports are saved. The text holds counts,
 * timings, mod names and versions; no player names, addresses or settings values.
 */
final class PerfReports {
	private final ReportFiles files;
	private volatile int keep = 20;

	PerfReports(Path dir) {
		this.files = new ReportFiles(dir);
	}

	void keep(int count) {
		keep = count;
	}

	/** Any thread. */
	Path write(String kind, Instant time, String text) throws IOException {
		return files.write(kind, time, text, keep);
	}

	List<Path> list(String kind) throws IOException {
		return files.list(kind);
	}

	/** A path as staff should look for it: relative to the server folder when possible. */
	static String relative(Path file) {
		try {
			Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
			Path abs = file.toAbsolutePath().normalize();
			return abs.startsWith(game) ? game.relativize(abs).toString().replace('\\', '/') : abs.getFileName().toString();
		} catch (RuntimeException e) {
			return file.getFileName().toString();
		}
	}

	/** Builds the report text. Server thread (it walks every entity). */
	static String build(MinecraftServer server, PerfMonitor monitor, String profilerStatus) {
		long now = System.nanoTime();
		StringBuilder out = new StringBuilder(16 * 1024);
		out.append("MejtaPerf report\n================\n\n");
		out.append("Created:   ").append(Instant.now()).append('\n');
		out.append("Uptime:    ").append(Fmt.duration(monitor.uptimeSeconds())).append('\n');
		out.append("Versions:  Minecraft ").append(version("minecraft")).append(", Fabric Loader ").append(version("fabricloader"))
				.append(", Java ").append(System.getProperty("java.version")).append(", MejtaCore ").append(MejtaCoreAPI.coreVersion())
				.append('\n');
		out.append(profilerStatus).append('\n');

		out.append("\nTicks\n-----\n");
		TickStats ticks = monitor.ticks();
		for (int seconds : new int[] {5, 60, 300, 900}) {
			TickStats.Window w = ticks.window(now, seconds);
			out.append(Fmt.f("  last %-6s TPS %-5s  MSPT mean %-6s median %-6s p95 %-6s max %-6s (%s ticks)%n", Fmt.duration(seconds),
					Fmt.tps(w.tps()), Fmt.ms(w.meanMs()), Fmt.ms(w.medianMs()), Fmt.ms(w.p95Ms()), Fmt.ms(w.maxMs()), Fmt.count(w.ticks())));
		}
		out.append(monitor.alarm().lagging() ? Fmt.f("  Lag alert active for %s, peak %s ms%n",
				Fmt.duration(monitor.alarm().lagNanos(now) / 1_000_000_000L), Fmt.ms(monitor.alarm().peakMs())) : "  No lag alert active\n");

		ServerStats.Snapshot stats = ServerStats.collect(server, monitor.levels().msPerTick());
		out.append("\nWorld\n-----\n");
		out.append(Fmt.f("  %s players, %s loaded chunks, %s entities%n", stats.players(), Fmt.count(stats.chunks()), Fmt.count(stats.entities())));
		out.append("\n  Dimension                        ms/tick (1 min)   chunks   entities\n");
		for (ServerStats.Level level : stats.levels()) {
			out.append(Fmt.f("  %-32s %15s %8s %10s%n", level.id(), Fmt.ms(level.msPerTick()), Fmt.count(level.chunks()), Fmt.count(level.entities())));
		}
		out.append("\n  Entity types (top 30)\n");
		stats.entityTypes().entrySet().stream().limit(30)
				.forEach(e -> out.append(Fmt.f("  %8s  %s%n", Fmt.count(e.getValue()), e.getKey())));
		out.append("\n  Chunks with the most entities (dimension, chunk x z, block x z)\n");
		for (ServerStats.Crowd c : stats.crowds()) {
			out.append(Fmt.f("  %8s  %s chunk %d %d (blocks %d %d), mostly %s%n", Fmt.count(c.entities()), c.level(), c.chunkX(), c.chunkZ(),
					c.chunkX() * 16 + 8, c.chunkZ() * 16 + 8, c.topType()));
		}
		out.append("\n  These are possible contributors to lag, not a diagnosis. The profiler shows where time actually goes.\n");

		out.append("\nJava\n----\n");
		JvmStats.Snapshot jvm = JvmStats.snapshot();
		double[] gc = monitor.jvm().gcLastMinute();
		out.append(Fmt.f("  Heap %s used, %s reserved, %s maximum%n", Fmt.bytes(jvm.heapUsed()), Fmt.bytes(jvm.heapCommitted()), Fmt.bytes(jvm.heapMax())));
		out.append(Fmt.f("  Garbage collection: %.0f collections, %.0f ms collector time in the last %s%n", gc[0], gc[1],
				Fmt.duration(Math.round(gc[2]))));
		out.append(Fmt.f("  CPU: process %s, whole machine %s, %d cores; %d threads%n", Fmt.percent(jvm.processCpu()), Fmt.percent(jvm.systemCpu()),
				jvm.processors(), jvm.threads()));

		out.append("\nMejta metrics (last ").append(Fmt.duration(Math.round(monitor.metrics().windowSeconds()))).append(")\n--------------\n");
		out.append(metricsTable(monitor.metrics().rates()));

		out.append("\nMejta addons\n------------\n");
		for (AddonInfo addon : MejtaCoreAPI.getAddons()) {
			out.append(Fmt.f("  %-16s %-10s %s%n", addon.id(), addon.version(), addon.state()));
		}
		out.append("\nMods (").append(FabricLoader.getInstance().getAllMods().size()).append(")\n----\n");
		FabricLoader.getInstance().getAllMods().stream()
				.map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
				.sorted()
				.forEach(line -> out.append("  ").append(line).append('\n'));
		return out.toString();
	}

	static String metricsTable(List<MetricRates.Rate> rates) {
		if (rates.isEmpty()) {
			return "  No metrics reported yet.\n";
		}
		StringBuilder out = new StringBuilder();
		out.append(Fmt.f("  %-44s %10s %12s %10s %10s%n", "timer", "calls/s", "avg µs", "ms/tick", "peak ms"));
		for (MetricRates.Rate r : rates) {
			switch (r.kind()) {
				case TIMER -> out.append(Fmt.f("  %-44s %10s %12s %10s %10s%n", r.id(), Fmt.f("%.1f", r.perSecond()),
						Double.isNaN(r.meanMicros()) ? "-" : Fmt.f("%.1f", r.meanMicros()), Fmt.ms(r.msPerTick()), Fmt.ms(r.peakMs())));
				case COUNTER -> out.append(Fmt.f("  %-44s %10s/s  total %s (counter)%n", r.id(), Fmt.f("%.1f", r.perSecond()), Fmt.count(r.value())));
				case GAUGE -> out.append(Fmt.f("  %-44s %10s (gauge)%n", r.id(), Fmt.count(r.value())));
			}
		}
		return out.toString();
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}
}
