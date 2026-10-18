package com.mejta.mejtadiscord.text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** At most {@code limit} events per sender in any sliding minute. Thread-safe. */
public final class UserRateLimiter {
	private static final long WINDOW_MS = 60_000;

	private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();
	private final LongSupplier clock;

	public UserRateLimiter(LongSupplier clockMillis) {
		this.clock = clockMillis;
	}

	public boolean tryAcquire(String sender, int limit) {
		long now = clock.getAsLong();
		Deque<Long> times = events.computeIfAbsent(sender, k -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) {
				times.pollFirst();
			}
			if (times.size() >= limit) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}
}
