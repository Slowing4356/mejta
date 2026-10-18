package com.mejta.mejtaac.violation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per player-and-key rate limiter that counts what it suppressed, so the next allowed event can
 * report "and N more". Used for alerts and records so a cheat sending 20 bad packets per second
 * cannot flood chat or disk.
 */
public final class Throttle {
	private record Key(UUID player, String key) {
	}

	private static final class State {
		long lastMs = Long.MIN_VALUE;
		int suppressed;
	}

	private final Map<Key, State> states = new ConcurrentHashMap<>();

	/**
	 * @return the number of events suppressed since the last allowed one (0 or more) if this event
	 *         is allowed, or {@code -1} if it is suppressed
	 */
	public int tryPass(UUID player, String key, long intervalMs, long nowMs) {
		State state = states.computeIfAbsent(new Key(player, key), k -> new State());
		synchronized (state) {
			if (state.lastMs != Long.MIN_VALUE && nowMs - state.lastMs < intervalMs) {
				state.suppressed++;
				return -1;
			}
			int suppressed = state.suppressed;
			state.suppressed = 0;
			state.lastMs = nowMs;
			return suppressed;
		}
	}

	public void forget(UUID player) {
		states.keySet().removeIf(k -> k.player().equals(player));
	}

	public void clear() {
		states.clear();
	}
}
