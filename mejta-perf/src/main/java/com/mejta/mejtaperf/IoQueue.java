package com.mejta.mejtaperf;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One daemon thread with a short queue for building and writing reports, so the server thread never touches the disk. */
final class IoQueue {
	private final ThreadPoolExecutor executor;
	private final Object lock = new Object();
	/** Tasks accepted and not finished yet, queued or running. Guarded by {@link #lock}. */
	private int pending;

	IoQueue() {
		executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
			Thread t = new Thread(r, "MejtaPerf-IO");
			t.setDaemon(true);
			return t;
		});
	}

	/** {@code false} when the queue is full or shut down. */
	boolean submit(Runnable task) {
		synchronized (lock) {
			pending++;
		}
		try {
			executor.execute(() -> {
				try {
					task.run();
				} finally {
					finished();
				}
			});
			return true;
		} catch (RejectedExecutionException e) {
			finished();
			return false;
		}
	}

	private void finished() {
		synchronized (lock) {
			pending--;
			lock.notifyAll();
		}
	}

	/**
	 * Waits up to {@code millis} until no task is queued or running, including tasks submitted meanwhile. Works
	 * with a full queue too. The queue stays usable.
	 */
	void drain(long millis) {
		long deadline = System.nanoTime() + millis * 1_000_000L;
		synchronized (lock) {
			while (pending > 0) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					return;
				}
				try {
					lock.wait(Math.max(1, left / 1_000_000L));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}
}
