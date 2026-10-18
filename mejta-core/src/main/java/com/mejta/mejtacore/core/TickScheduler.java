package com.mejta.mejtacore.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import com.mejta.mejtacore.api.metrics.Metrics;
import com.mejta.mejtacore.api.server.Scheduler;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.metrics.MetricRegistry;

/** {@link Scheduler} driven by the end of each server tick. */
public final class TickScheduler implements Scheduler {
	private static final TickScheduler INSTANCE = new TickScheduler();

	private final Metrics.Timer taskTime = MetricRegistry.get().timer(MejtaCore.MOD_ID, "scheduler.tasks");
	private final List<Entry> tasks = new ArrayList<>();
	private final List<Entry> added = new ArrayList<>();
	private long tick;

	private static final class Entry implements Task {
		final Runnable task;
		final int interval;
		long due;
		volatile boolean cancelled;

		Entry(Runnable task, int interval, long due) {
			this.task = task;
			this.interval = interval;
			this.due = due;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}
	}

	private TickScheduler() {
	}

	public static TickScheduler get() {
		return INSTANCE;
	}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> INSTANCE.onTick());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> INSTANCE.clear());
	}

	@Override
	public Task runLater(int ticks, Runnable task) {
		return add(task, 0, Math.max(1, ticks));
	}

	@Override
	public Task runEvery(int intervalTicks, Runnable task) {
		int interval = Math.max(1, intervalTicks);
		return add(task, interval, interval);
	}

	private synchronized Task add(Runnable task, int interval, int delay) {
		Entry entry = new Entry(Objects.requireNonNull(task, "task"), interval, tick + delay);
		added.add(entry);
		return entry;
	}

	private void onTick() {
		List<Entry> due = new ArrayList<>();
		synchronized (this) {
			tick++;
			tasks.addAll(added);
			added.clear();
			tasks.removeIf(e -> e.cancelled);
			for (Entry e : tasks) {
				if (e.due <= tick) {
					due.add(e);
				}
			}
		}
		for (Entry e : due) {
			if (e.cancelled) {
				continue;
			}
			long start = Metrics.Timer.start();
			try {
				e.task.run();
			} catch (Throwable t) {
				Diagnostics.error("scheduler", "Scheduled task failed", t);
			}
			taskTime.stop(start);
			if (e.interval > 0) {
				e.due += e.interval;
			} else {
				e.cancelled = true;
			}
		}
	}

	private synchronized void clear() {
		tasks.forEach(Entry::cancel);
		added.forEach(Entry::cancel);
		tasks.clear();
		added.clear();
	}
}
