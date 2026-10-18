package com.mejta.mejtadiscord.text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * At most {@code perSender} events per sender, and {@code global} events in total, in any sliding
 * minute. Thread-safe. Memory stays bounded: senders with nothing left in their window are forgotten,
 * and past {@code maxSenders} the least recently active one is dropped.
 */
public final class UserRateLimiter {
	private static final long WINDOW_MS = 60_000;
	public static final int DEFAULT_MAX_SENDERS = 10_000;

	/** Ordered by last accepted event, oldest first: entries are moved to the end when used. */
	private final Map<String, Deque<Long>> events = new LinkedHashMap<>();
	private final Deque<Long> all = new ArrayDeque<>();
	private final LongSupplier clock;
	private final int maxSenders;

	public UserRateLimiter(LongSupplier clockMillis) {
		this(clockMillis, DEFAULT_MAX_SENDERS);
	}

	public UserRateLimiter(LongSupplier clockMillis, int maxSenders) {
		this.clock = clockMillis;
		this.maxSenders = Math.max(1, maxSenders);
	}

	public boolean tryAcquire(String sender, int limit) {
		return tryAcquire(sender, limit, Integer.MAX_VALUE);
	}

	/**
	 * The global cap is checked first. A refused event uses up neither budget, so one sender being
	 * refused cannot use up everyone else's share.
	 */
	public synchronized boolean tryAcquire(String sender, int perSender, int global) {
		long now = clock.getAsLong();
		expire(all, now);
		evictIdle(now);
		if (all.size() >= global) {
			return false;
		}
		Deque<Long> times = events.get(sender);
		if (times != null) {
			expire(times, now);
			if (times.size() >= perSender) {
				return false;
			}
			events.remove(sender); // re-inserted at the end: the most recently active
		} else {
			times = new ArrayDeque<>();
		}
		times.addLast(now);
		all.addLast(now);
		events.put(sender, times);
		while (events.size() > maxSenders) {
			Iterator<String> eldest = events.keySet().iterator();
			eldest.next();
			eldest.remove();
		}
		return true;
	}

	/** Senders currently remembered (for tests). */
	synchronized int trackedSenders() {
		return events.size();
	}

	private static void expire(Deque<Long> times, long now) {
		while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
			times.pollFirst();
		}
	}

	/** The map is in order of last activity, so idle senders are at its start. */
	private void evictIdle(long now) {
		for (Iterator<Deque<Long>> it = events.values().iterator(); it.hasNext();) {
			Deque<Long> times = it.next();
			if (!times.isEmpty() && now - times.peekLast() < WINDOW_MS) {
				return;
			}
			it.remove();
		}
	}
}
