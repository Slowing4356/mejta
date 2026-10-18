package com.mejta.mejtaperf;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One daemon thread with a short queue for building and writing reports, so the server thread never touches the disk. */
final class IoQueue {
	private final ThreadPoolExecutor executor;

	IoQueue() {
		executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
			Thread t = new Thread(r, "MejtaPerf-IO");
			t.setDaemon(true);
			return t;
		});
	}

	/** {@code false} when the queue is full or shut down. */
	boolean submit(Runnable task) {
		try {
			executor.execute(task);
			return true;
		} catch (RejectedExecutionException e) {
			return false;
		}
	}

	/** Waits up to {@code millis} for the tasks queued so far to finish. The queue stays usable. */
	void drain(long millis) {
		CountDownLatch done = new CountDownLatch(1);
		if (!submit(done::countDown)) {
			return;
		}
		try {
			done.await(millis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
