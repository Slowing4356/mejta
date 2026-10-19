package com.mejta.mejtaqol.afk;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is AFK and since when each player was last active. Times are passed in (milliseconds), so the
 * rules are easy to test. Thread-safe.
 */
public final class AfkClock {
	/** What changed for a player at a check. */
	public enum Change {
		NONE,
		BECAME_AFK,
		RETURNED,
		KICK
	}

	private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
	private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

	public void join(UUID player, long nowMillis) {
		lastActive.put(player, nowMillis);
		afk.remove(player);
	}

	public void leave(UUID player) {
		lastActive.remove(player);
		afk.remove(player);
	}

	public boolean isAfk(UUID player) {
		return afk.contains(player);
	}

	/** Milliseconds since the player's last activity ({@code 0} if unknown). */
	public long idleMillis(UUID player, long nowMillis) {
		Long last = lastActive.get(player);
		return last == null ? 0 : Math.max(0, nowMillis - last);
	}

	/** The player did something. Returns {@link Change#RETURNED} if that ends their AFK state. */
	public Change active(UUID player, long nowMillis) {
		lastActive.put(player, nowMillis);
		return afk.remove(player) ? Change.RETURNED : Change.NONE;
	}

	/** {@code /afk}: toggles. Returns the new state's change ({@link Change#BECAME_AFK} or {@link Change#RETURNED}). */
	public Change toggle(UUID player, long nowMillis) {
		if (afk.remove(player)) {
			lastActive.put(player, nowMillis);
			return Change.RETURNED;
		}
		afk.add(player);
		return Change.BECAME_AFK;
	}

	/**
	 * Periodic check. {@code autoMinutes} (0 = off) makes an idle player AFK; {@code kickMinutes}
	 * (0 = off) asks to kick a player idle that long, unless {@code kickExempt}.
	 */
	public Change check(UUID player, long nowMillis, int autoMinutes, int kickMinutes, boolean kickExempt) {
		long idle = idleMillis(player, nowMillis);
		if (kickMinutes > 0 && !kickExempt && idle >= kickMinutes * 60_000L) {
			return Change.KICK;
		}
		if (autoMinutes > 0 && !afk.contains(player) && idle >= autoMinutes * 60_000L) {
			afk.add(player);
			return Change.BECAME_AFK;
		}
		return Change.NONE;
	}

	public void clear() {
		lastActive.clear();
		afk.clear();
	}
}
