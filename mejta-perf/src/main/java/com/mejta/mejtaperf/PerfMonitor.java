package com.mejta.mejtaperf;

import java.util.IdentityHashMap;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtaperf.metrics.MetricRates;
import com.mejta.mejtaperf.profiler.TickLog;
import com.mejta.mejtaperf.tick.LagAlarm;
import com.mejta.mejtaperf.tick.LevelTimes;
import com.mejta.mejtaperf.tick.TickStats;

/**
 * Measures every server tick and every dimension's tick through Fabric's tick events, and once a second feeds
 * the lag alarm and the metric, dimension and garbage collection histories. Per tick it costs two clock reads
 * per event and no allocation. All state is touched on the server thread only, except {@link #inTick()},
 * {@link #tickStartNanos()} and {@link #serverThread()}, which the profiler and the watchdog read.
 */
final class PerfMonitor {
	interface SecondListener {
		void secondPassed(MinecraftServer server, long now, LagAlarm.Event event);
	}

	private final TickStats ticks = new TickStats();
	private final TickLog tickLog = new TickLog();
	private final LevelTimes levels = new LevelTimes();
	private final MetricRates metrics = new MetricRates();
	private final JvmStats jvm = new JvmStats();
	private final LagAlarm alarm = new LagAlarm(50, 10_000_000_000L, 600_000_000_000L);
	private final Map<ServerLevel, String> levelIds = new IdentityHashMap<>();
	private volatile boolean inTick;
	private volatile long tickStartNanos;
	private volatile Thread serverThread;
	private long nextSecond;
	private long startedAt;
	private SecondListener listener = (server, now, event) -> {
	};

	void register(SecondListener secondListener) {
		this.listener = secondListener;
		ServerTickEvents.START_SERVER_TICK.register(this::tickStarted);
		ServerTickEvents.END_SERVER_TICK.register(this::tickEnded);
		ServerTickEvents.START_LEVEL_TICK.register(level -> levels.started(levelId(level), System.nanoTime()));
		ServerTickEvents.END_LEVEL_TICK.register(level -> levels.ended(levelId(level), System.nanoTime()));
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Runs on the server thread. A singleplayer world can be closed and another opened in the same game,
			// each with its own server thread: start from nothing.
			serverThread = Thread.currentThread();
			levelIds.clear();
			inTick = false;
			ticks.reset();
			levels.reset();
			metrics.reset();
			alarm.reset();
			nextSecond = System.nanoTime();
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> startedAt = System.nanoTime());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			levelIds.clear();
			serverThread = null;
			startedAt = 0;
		});
	}

	private String levelId(ServerLevel level) {
		return levelIds.computeIfAbsent(level, l -> l.dimension().identifier().toString());
	}

	private void tickStarted(MinecraftServer server) {
		long now = System.nanoTime();
		// The previous tick never ended: a dedicated server paused because it is empty returns from tickServer
		// before the end event. TickStats drops that start. Paused ticks only handle connections and are not
		// marked as ticks, so inTick does not stay true for good (the first real tick after a pause is not marked).
		boolean unended = ticks.inTick();
		ticks.tickStarted(now);
		tickLog.started();
		tickStartNanos = now; // before inTick: whoever sees inTick true sees this start or a newer one
		inTick = !unended;
	}

	private void tickEnded(MinecraftServer server) {
		long now = System.nanoTime();
		ticks.tickEnded(now);
		tickLog.ended(now - tickStartNanos); // before inTick: the profiler reads the duration once the tick is over
		inTick = false;
		if (now - nextSecond < 0) {
			return;
		}
		nextSecond = now + 1_000_000_000L;
		// One tick longer than a second leaves the last second empty: then that tick is the measurement.
		TickStats.Window second = ticks.window(now, 1);
		LagAlarm.Event event = second.ticks() > 0
				? alarm.feed(now, now - 1_000_000_000L, second.meanMs())
				: alarm.feed(now, ticks.lastTickStart(), ticks.lastTickNanos() / 1e6);
		metrics.add(now, ticks.totalTicks(), MejtaCoreAPI.metrics().snapshot());
		levels.secondPassed();
		jvm.secondPassed();
		listener.secondPassed(server, now, event);
	}

	void configureAlarm(int msptThreshold, int sustainSeconds, int cooldownMinutes) {
		alarm.configure(msptThreshold, sustainSeconds * 1_000_000_000L, cooldownMinutes * 60_000_000_000L);
	}

	TickStats ticks() {
		return ticks;
	}

	/** Recent tick durations, readable from the profiler's thread. */
	TickLog tickLog() {
		return tickLog;
	}

	LevelTimes levels() {
		return levels;
	}

	MetricRates metrics() {
		return metrics;
	}

	JvmStats jvm() {
		return jvm;
	}

	LagAlarm alarm() {
		return alarm;
	}

	/** Seconds since the server finished starting, or 0 before. */
	long uptimeSeconds() {
		return startedAt == 0 ? 0 : (System.nanoTime() - startedAt) / 1_000_000_000L;
	}

	boolean inTick() {
		return inTick;
	}

	/** When the current (or last) tick started, {@link System#nanoTime()} scale. */
	long tickStartNanos() {
		return tickStartNanos;
	}

	/** The server thread, known from server start on; {@code null} before and after the server runs. */
	Thread serverThread() {
		return serverThread;
	}
}
