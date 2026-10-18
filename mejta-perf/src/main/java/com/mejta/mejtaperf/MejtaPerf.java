package com.mejta.mejtaperf;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtaperf.metrics.MetricRates;
import com.mejta.mejtaperf.tick.LagAlarm;
import com.mejta.mejtaperf.tick.TickStats;

/**
 * MejtaPerf entrypoint: a tick monitor that is always on (and costs almost nothing), lag alerts as MejtaCore
 * notifications, a profiler started on demand, and reports saved to {@code mejta-data/mejtaperf/reports/}.
 * It reads other addons only through MejtaCore's metrics.
 */
public final class MejtaPerf implements MejtaAddon {
	public static final String ID = "mejtaperf";
	/** Sustained lag and its recovery. */
	public static final String CHANNEL_LAG = ID + ".lag";
	/** A finished profile. */
	public static final String CHANNEL_PROFILER = ID + ".profiler";

	private static final int AUTO_PROFILE_SECONDS = 30;

	/** Settings read once per change. */
	record Snapshot(boolean alerts, int msptThreshold, int sustainSeconds, boolean recovery, boolean autoProfile, int profilerInterval,
			int profilerMax) {
	}

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile Snapshot snapshot;
	private static volatile MinecraftServer server;
	private static volatile IoQueue io;
	private static volatile PerfReports reports;
	private static final PerfMonitor MONITOR = new PerfMonitor();
	private static volatile Profiler profiler;
	private static volatile Watchdog watchdog;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaPerf")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("TPS and MSPT monitor, lag alerts, a profiler and performance reports")
				.icon("minecraft:clock")
				.config(PerfSettings.SPEC)
				.permissions(PerfPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					profiler = new Profiler(MONITOR, log);
					reports = new PerfReports(MejtaCoreAPI.dataDirectory(ID).resolve("reports"));
					settingsChanged();
					config.addChangeListener(MejtaPerf::settingsChanged);
					MONITOR.register(MejtaPerf::secondPassed);
					PerfCommands.register();
					io = new IoQueue();
					watchdog = new Watchdog(MONITOR::inTick, MONITOR::tickStartNanos, () -> snapshot.sustainSeconds() * 1_000_000_000L,
							MejtaPerf::stalled, log);
					ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
					ServerLifecycleEvents.SERVER_STARTED.register(s -> watchdog.start());
					ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
						// From here on nothing is handed to the server thread: once stopped, execute() would run it inline.
						server = null;
						watchdog.stop();
						profiler.cancel();
					});
					ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
						server = null;
						io.drain(5_000); // reports being written finish before the game exits
					});
					log.info("MejtaPerf initialized");
				})
				.build());
	}

	private static void settingsChanged() {
		ConfigHandle c = config;
		Snapshot s = new Snapshot(c.get(PerfSettings.ALERTS), c.get(PerfSettings.ALERT_MSPT), c.get(PerfSettings.ALERT_SUSTAIN),
				c.get(PerfSettings.ALERT_RECOVERY), c.get(PerfSettings.ALERT_PROFILE), c.get(PerfSettings.PROFILER_INTERVAL),
				c.get(PerfSettings.PROFILER_MAX));
		snapshot = s;
		reports.keep(c.get(PerfSettings.REPORTS_KEEP));
		int cooldown = c.get(PerfSettings.ALERT_COOLDOWN);
		// The alarm belongs to the server thread; when no server is running, nothing else touches it.
		Runnable apply = () -> MONITOR.configureAlarm(s.msptThreshold(), s.sustainSeconds(), cooldown);
		if (!runOnServer(server, apply)) {
			apply.run();
		}
	}

	/**
	 * Hands a task to {@code target}'s server thread if it is the server that is running and not stopping.
	 * Returns {@code false} (and drops the task) otherwise.
	 */
	static boolean runOnServer(MinecraftServer target, Runnable task) {
		MinecraftServer running = server;
		if (target == null || target != running) {
			return false;
		}
		running.execute(task);
		return true;
	}

	// ---------------------------------------------------------------- alerts

	/** Server thread, once a second. */
	private static void secondPassed(MinecraftServer s, long now, LagAlarm.Event event) {
		Snapshot settings = snapshot;
		if (event == LagAlarm.Event.LAG) {
			if (settings.alerts()) {
				publishLag(s, now, settings);
			}
			if (settings.autoProfile() && !profiler.running()) {
				// Only the slow ticks: the report then shows what made them slow, not the normal ticks around them.
				String problem = profiler.start(s, Math.min(AUTO_PROFILE_SECONDS, settings.profilerMax()), settings.profilerInterval(),
						settings.msptThreshold(), "lag alert", MejtaPerf::announce);
				if (problem != null) {
					log.info("No automatic profile: {}", problem);
				}
			}
		} else if (event == LagAlarm.Event.RECOVERED && settings.alerts() && settings.recovery()) {
			LagAlarm alarm = MONITOR.alarm();
			publish(Notification.builder(ID, CHANNEL_LAG)
					.severity(Severity.INFO)
					.title("Server recovered")
					.message(Fmt.f("MSPT is back below %s ms after %s of lag (peak %s ms).", Fmt.ms(settings.msptThreshold() * 0.8),
							Fmt.duration(alarm.lagNanos(now) / 1_000_000_000L), Fmt.ms(alarm.peakMs())))
					.field("peak_mspt", Fmt.ms(alarm.peakMs()))
					.field("duration", Fmt.duration(alarm.lagNanos(now) / 1_000_000_000L)));
		}
	}

	private static void publishLag(MinecraftServer s, long now, Snapshot settings) {
		// One freeze longer than the sustain time leaves the window empty: then it covers that tick.
		TickStats.Window w = MONITOR.ticks().windowOrLastTick(now, Math.max(1, settings.sustainSeconds()));
		double mean = Double.isNaN(w.meanMs()) ? MONITOR.alarm().peakMs() : w.meanMs();
		long lagSeconds = Math.max(settings.sustainSeconds(), MONITOR.alarm().lagNanos(now) / 1_000_000_000L);
		ServerStats.Snapshot stats = ServerStats.collect(s, MONITOR.levels().msPerTick());
		String slowest = stats.levels().stream().filter(l -> !Double.isNaN(l.msPerTick()))
				.max(Comparator.comparingDouble(ServerStats.Level::msPerTick))
				.map(l -> PerfCommands.shortId(l.id()) + " " + Fmt.ms(l.msPerTick()) + " ms/tick").orElse("-");
		List<MetricRates.Rate> timers = MONITOR.metrics().rates().stream()
				.filter(r -> r.kind() == com.mejta.mejtacore.api.metrics.MetricSnapshot.Kind.TIMER && !Double.isNaN(r.msPerTick()))
				.limit(3).toList();
		String metrics = timers.isEmpty() ? "-" : String.join(", ", timers.stream().map(r -> r.id() + " " + Fmt.ms(r.msPerTick()) + " ms/tick").toList());
		publish(Notification.builder(ID, CHANNEL_LAG)
				.severity(Severity.WARNING)
				.title("Server lag: " + Fmt.ms(mean) + " ms per tick")
				.message(Fmt.f("MSPT has stayed at or above %d ms for %s (TPS %s). The figures below are possible contributors, not a diagnosis; "
						+ "run /mperf profiler start to see where the time goes.", settings.msptThreshold(), Fmt.duration(lagSeconds),
						Fmt.tps(w.tps())))
				.field("mspt", Fmt.ms(mean) + " mean, " + Fmt.ms(Double.isNaN(w.maxMs()) ? mean : w.maxMs()) + " max")
				.field("tps", Fmt.tps(w.tps()))
				.field("players", stats.players())
				.field("chunks", Fmt.count(stats.chunks()))
				.field("entities", Fmt.count(stats.entities()))
				.field("top_entities", stats.topTypes(3))
				.field("slowest_dimension", slowest)
				.field("top_metrics", metrics));
	}

	/**
	 * Watchdog thread, once per stall: a tick has run for longer than the sustain time without ending, and the lag
	 * alarm only hears of it when (if) it ends. Reads only volatile settings and publishes, which is thread-safe.
	 */
	private static void stalled(long nanos) {
		Snapshot settings = snapshot;
		if (!settings.alerts()) {
			return;
		}
		String stuck = Fmt.duration(nanos / 1_000_000_000L);
		publish(Notification.builder(ID, CHANNEL_LAG)
				.severity(Severity.WARNING)
				.title("Server stuck in one tick")
				.message("The server has been stuck in one tick for " + stuck + ". If it does not recover, a thread dump of the server "
						+ "(jstack) shows where it hangs.")
				.field("stuck_for", stuck));
	}

	/**
	 * Tells profiler holders that a profile finished. Any thread (it only publishes). The reason never names who
	 * started the profile: notifications can be forwarded anywhere, unlike the report file.
	 */
	static void announce(Profiler.Result result) {
		String reason = Profiler.publicReason(result.reason());
		Notification.Builder n = Notification.builder(ID, CHANNEL_PROFILER).inGameAudience(PerfPermissions.PROFILER);
		if (result.file() == null) {
			n.severity(Severity.WARNING).title("Profile not saved")
					.message("The profile (" + reason + ") could not be saved: " + result.error() + ". See the server log.");
		} else {
			n.severity(Severity.INFO).title("Profile saved")
					.message("Profile (" + reason + ") saved to " + PerfReports.relative(result.file()) + ". " + String.join(" ", result.summary()));
			n.field("report", PerfReports.relative(result.file()));
		}
		MejtaCoreAPI.notifications().publish(n.build());
	}

	private static void publish(Notification.Builder builder) {
		MejtaCoreAPI.notifications().publish(builder.inGameAudience(PerfPermissions.ALERTS).build());
	}

	// ---------------------------------------------------------------- accessors

	static Snapshot snapshot() {
		return snapshot;
	}

	static PerfMonitor monitor() {
		return MONITOR;
	}

	static Profiler profiler() {
		return profiler;
	}

	static IoQueue io() {
		return io;
	}

	static PerfReports reports() {
		return reports;
	}

	static MinecraftServer server() {
		return server;
	}

	static Logger log() {
		return log;
	}
}
