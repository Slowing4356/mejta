package com.mejta.mejtaperf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class IoQueueTest {
	@Test
	void drainWaitsForEveryTaskEvenWithAFullQueue() throws InterruptedException {
		IoQueue io = new IoQueue();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch running = new CountDownLatch(1);
		AtomicInteger done = new AtomicInteger();
		assertTrue(io.submit(() -> {
			running.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			done.incrementAndGet();
		}));
		assertTrue(running.await(5, TimeUnit.SECONDS));
		int queued = 0;
		while (io.submit(done::incrementAndGet)) {
			queued++;
		}
		assertEquals(8, queued, "the queue is full");

		long before = System.nanoTime();
		io.drain(100);
		assertTrue(System.nanoTime() - before >= 90_000_000L, "drain waited for its timeout although the queue was full");
		assertEquals(0, done.get());

		release.countDown();
		io.drain(5_000);
		assertEquals(9, done.get(), "every task finished before drain returned");
		assertTrue(io.submit(() -> {
		}), "still usable");
		io.drain(5_000);
		assertFalse(Thread.currentThread().isInterrupted());
	}
}
