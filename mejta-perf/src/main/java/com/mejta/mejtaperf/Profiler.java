package com.mejta.mejtaperf;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;

import net.minecraft.server.MinecraftServer;

import com.mejta.mejtaperf.profiler.Attribution;
import com.mejta.mejtaperf.profiler.ProfileReport;
import com.mejta.mejtaperf.profiler.StackSampler;
import com.mejta.mejtaperf.tick.TickStats;

/**
 * At most one profiling run at a time. A run samples the server thread until it is stopped or reaches its time
 * limit; then the report is built and saved on MejtaPerf's I/O thread and the result is handed back on the
 * server thread. When the server stops, a run still sampling is cancelled without a report; one that had
 * already ended has its report written all the same (the shutdown waits for the I/O thread).
 *
 * <p>Runs are started, stopped and finished on the server thread; {@link #current} and {@link #last} are
 * volatile so that status lines may be read from anywhere.
 */
final class Profiler {
	/**
	 * The outcome of a run, delivered on the server thread, or on the I/O thread once the server has stopped.
	 * {@code reason} may name who started it.
	 */
	record Result(String reason, Path file, List<String> summary, String error) {
	}

	private static final String BY_COMMAND = "started by ";

	private final PerfMonitor monitor;
	private final Logger log;
	private volatile Run current;
	private volatile Result last;

	private final class Run {
		final StackSampler sampler;
		final String reason;
		final Instant started = Instant.now();
		final long startTicks;
		final long startNanos;
		final Consumer<Result> done;

		Run(StackSampler sampler, String reason, Consumer<Result> done) {
			this.sampler = sampler;
			this.reason = reason;
			this.done = done;
			this.startTicks = monitor.ticks().totalTicks();
			this.startNanos = System.nanoTime();
		}
	}

	Profiler(PerfMonitor monitor, Logger log) {
		this.monitor = monitor;
		this.log = log;
	}

	/**
	 * Starts a run. Server thread only.
	 *
	 * @param reason why it runs, shown in the report ("started by Alex", "lag alert")
	 * @return {@code null} on success, otherwise why it could not start
	 */
	String start(MinecraftServer server, int seconds, int intervalMs, String reason, Consumer<Result> done) {
		if (current != null) {
			return "A profile is already running (" + current.reason + ").";
		}
		Thread thread = monitor.serverThread();
		if (thread == null) {
			return "The server thread is not known yet; try again in a moment.";
		}
		Run[] holder = new Run[1];
		// Once the server is stopping, the sampler no longer hands the run back: cancel() has dealt with it.
		StackSampler sampler = new StackSampler(thread, intervalMs * 1_000_000L, seconds * 1_000_000_000L, monitor::inTick, System::nanoTime,
				() -> MejtaPerf.runOnServer(server, () -> finished(holder[0])));
		holder[0] = new Run(sampler, reason, done);
		current = holder[0];
		sampler.start();
		log.info("Profiler started ({}) for at most {} s, sampling every {} ms", reason, seconds, intervalMs);
		return null;
	}

	/** Ends the current run; its report is still written. Returns {@code false} if none was running. */
	boolean stop() {
		Run run = current;
		if (run == null) {
			return false;
		}
		run.sampler.stop(StackSampler.End.STOPPED);
		return true;
	}

	/**
	 * Server shutdown, on the server thread: ends a run that is still sampling without a report. A run that had
	 * already ended (stopped or time limit) but not yet been handed back gets its report now.
	 */
	void cancel() {
		Run run = current;
		if (run == null) {
			return;
		}
		run.sampler.stop(StackSampler.End.CANCELLED);
		if (run.sampler.end() == StackSampler.End.CANCELLED) {
			current = null;
			return;
		}
		try {
			run.sampler.await(1_000); // its last sample may still be going into the tree
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finished(run);
	}

	boolean running() {
		return current != null;
	}

	/** One line about the current or last run, naming who started it. */
	String status() {
		return status(true);
	}

	/** @param names whether to say who started a running profile (not in reports, which name no players) */
	String status(boolean names) {
		Run run = current;
		if (run != null) {
			String why = names ? run.reason : publicReason(run.reason);
			return Fmt.f("Profiler: running for %s (%s), %s samples", Fmt.duration(run.sampler.elapsedNanos() / 1_000_000_000L), why,
					Fmt.count(run.sampler.samples()));
		}
		Result r = last;
		if (r == null) {
			return "Profiler: not running";
		}
		return r.file() != null ? "Profiler: not running; last report " + PerfReports.relative(r.file()) : "Profiler: not running; last run failed";
	}

	/** Why a run was started, without naming anyone: for notifications, which may be forwarded anywhere. */
	static String publicReason(String reason) {
		return reason.startsWith(BY_COMMAND) ? "started from a command" : reason;
	}

	/** Runs on the server thread once the sampler thread has finished. */
	private void finished(Run run) {
		if (run == null || current != run || Thread.currentThread() != monitor.serverThread()) {
			return; // cancelled, or run late by a server that has stopped
		}
		current = null;
		StackSampler sampler = run.sampler;
		String endReason = switch (sampler.end()) {
			case STOPPED -> "stopped";
			case TIME_LIMIT -> "time limit reached";
			case THREAD_DIED -> "server thread ended";
			default -> sampler.end().name().toLowerCase(java.util.Locale.ROOT);
		};
		long now = System.nanoTime();
		long elapsed = Math.max(1, now - run.startNanos);
		long tickCount = monitor.ticks().totalTicks() - run.startTicks;
		// TPS counts every tick of the run. MSPT comes from the tick history, which holds the last 15 minutes (at
		// 20 TPS), so a longer run is covered only in part, and the label says so.
		int seconds = (int) Math.min(Integer.MAX_VALUE, (elapsed + 999_999_999L) / 1_000_000_000L);
		TickStats.Window window = monitor.ticks().window(now, seconds);
		String span = tickCount <= TickStats.CAPACITY ? "the whole run"
				: "its last " + Fmt.count(window.ticks()) + " ticks (the history holds 15 minutes)";
		String ticks = Fmt.f("%s ticks, TPS %s; MSPT over %s: mean %s ms, max %s ms", Fmt.count(tickCount),
				Fmt.tps(tickCount / (elapsed / 1e9)), span, Fmt.ms(window.meanMs()), Fmt.ms(window.maxMs()));
		ProfileReport.Info info = new ProfileReport.Info(run.started.toString() + " (" + run.reason + ")", endReason,
				sampler.elapsedNanos() / 1e9, sampler.intervalNanos() / 1e6, ticks);
		boolean queued = MejtaPerf.io().submit(() -> {
			Result result;
			try {
				ProfileReport report = new ProfileReport(sampler.tree(),
						new Attribution(new ModOwners(MejtaPerf.class.getClassLoader()), ModOwners.modIds()));
				Path file = MejtaPerf.reports().write("profile", run.started, report.render(info));
				result = new Result(run.reason, file, report.chatLines(5, 5), null);
			} catch (Exception | LinkageError e) {
				log.error("Could not write the profile report", e);
				result = new Result(run.reason, null, List.of(), e.getClass().getSimpleName());
			}
			Result r = result;
			Runnable deliver = () -> {
				last = r;
				run.done.accept(r);
			};
			if (!MejtaPerf.runOnServer(MejtaPerf.server(), deliver)) {
				deliver.run(); // the server has stopped (the report was finished during shutdown): no server thread to wait for
			}
		});
		if (!queued) {
			last = new Result(run.reason, null, List.of(), "busy");
			run.done.accept(last);
		}
	}
}
