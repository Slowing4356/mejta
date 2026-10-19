package com.mejta.mejtacore.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rules behind AFK and combat, with times passed in (milliseconds) so they are easy to test.
 * Thread-safe.
 */
public final class StatusClock {
	/** What an AFK update changed. */
	public enum Change {
		NONE,
		BECAME_AFK,
		RETURNED
	}

	private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
	private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();

	public void join(UUID player, long nowMillis) {
		lastActive.put(player, nowMillis);
		afk.remove(player);
	}

	public void leave(UUID player) {
		lastActive.remove(player);
		afk.remove(player);
		lastCombat.remove(player);
	}

	public boolean isAfk(UUID player) {
		return afk.contains(player);
	}

	public long idleMillis(UUID player, long nowMillis) {
		Long last = lastActive.get(player);
		return last == null ? 0 : Math.max(0, nowMillis - last);
	}

	/** The player did something. Returns {@link Change#RETURNED} if that ends their AFK state. */
	public Change active(UUID player, long nowMillis) {
		if (!lastActive.containsKey(player)) {
			return Change.NONE; // not online (yet)
		}
		lastActive.put(player, nowMillis);
		return afk.remove(player) ? Change.RETURNED : Change.NONE;
	}

	/** Sets or clears AFK on request. Clearing it counts as activity. */
	public Change set(UUID player, boolean value, long nowMillis) {
		lastActive.putIfAbsent(player, nowMillis);
		if (value) {
			return afk.add(player) ? Change.BECAME_AFK : Change.NONE;
		}
		lastActive.put(player, nowMillis);
		return afk.remove(player) ? Change.RETURNED : Change.NONE;
	}

	/** Periodic check: makes a player idle for {@code autoMinutes} (0 = off) AFK. */
	public Change check(UUID player, long nowMillis, int autoMinutes) {
		if (autoMinutes > 0 && lastActive.containsKey(player) && !afk.contains(player)
				&& idleMillis(player, nowMillis) >= autoMinutes * 60_000L) {
			afk.add(player);
			return Change.BECAME_AFK;
		}
		return Change.NONE;
	}

	/** Two players fought. */
	public void combat(UUID a, UUID b, long nowMillis) {
		lastCombat.put(a, nowMillis);
		lastCombat.put(b, nowMillis);
	}

	public void endCombat(UUID player) {
		lastCombat.remove(player);
	}

	/** Milliseconds of combat left with a tag of {@code tagSeconds} (0 = off). */
	public long combatRemaining(UUID player, long nowMillis, int tagSeconds) {
		Long last = lastCombat.get(player);
		if (tagSeconds <= 0 || last == null) {
			return 0;
		}
		return Math.max(0, last + tagSeconds * 1000L - nowMillis);
	}

	public void clear() {
		lastActive.clear();
		afk.clear();
		lastCombat.clear();
	}
}
