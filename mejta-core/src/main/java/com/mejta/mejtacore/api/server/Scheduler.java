package com.mejta.mejtacore.api.server;

/**
 * Runs code on the server thread after a delay or repeatedly, counted in server ticks. Obtain it
 * with {@code MejtaCoreAPI.scheduler()}. Tasks run at the end of a server tick; an exception in one
 * task is logged and does not affect others. All tasks are dropped when the server stops.
 *
 * @since API version 4
 */
public interface Scheduler {
	/** A scheduled task. */
	interface Task {
		/** Stops the task; it will not run again. Safe to call from inside the task. */
		void cancel();

		boolean isCancelled();
	}

	/** Runs {@code task} once, {@code ticks} ticks from now (at least 1). */
	Task runLater(int ticks, Runnable task);

	/** Runs {@code task} every {@code intervalTicks} ticks (at least 1), first after one interval. */
	Task runEvery(int intervalTicks, Runnable task);
}
