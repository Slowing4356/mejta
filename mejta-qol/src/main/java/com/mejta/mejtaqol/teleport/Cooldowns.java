package com.mejta.mejtaqol.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each player last used each feature, for cooldowns. Times are passed in (milliseconds), so the
 * class is easy to test. Kept in memory only: a restart clears every cooldown.
 */
public final class Cooldowns {
	private record Key(UUID player, String feature) {
	}

	private final Map<Key, Long> lastUse = new ConcurrentHashMap<>();

	/** Marks the feature as used now. */
	public void use(UUID player, String feature, long nowMillis) {
		lastUse.put(new Key(player, feature), nowMillis);
	}

	/** Milliseconds until the feature may be used again with a cooldown of {@code seconds}; {@code 0} if ready. */
	public long remaining(UUID player, String feature, int seconds, long nowMillis) {
		if (seconds <= 0) {
			return 0;
		}
		Long last = lastUse.get(new Key(player, feature));
		if (last == null) {
			return 0;
		}
		return Math.max(0, last + seconds * 1000L - nowMillis);
	}

	public void clear(UUID player) {
		lastUse.keySet().removeIf(k -> k.player().equals(player));
	}

	public void clearAll() {
		lastUse.clear();
	}
}
