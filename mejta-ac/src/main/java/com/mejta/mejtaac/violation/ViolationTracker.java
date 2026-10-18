package com.mejta.mejtaac.violation;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Violation levels per player and check, decaying linearly over time. Memory is bounded by
 * online players times checks: entries are removed when a player disconnects or decays to zero.
 * Thread-safe; time is passed in so the logic can be tested deterministically.
 *
 * <p>Each check keeps two levels that decay alike: the total, fed by every flag (alerts, records,
 * commands), and the <em>enforceable</em> level, fed only by flags confident enough to enforce. Only
 * the enforceable level is compared with the kick threshold, so low-confidence noise can never add
 * up with one confident flag to a kick.
 */
public final class ViolationTracker {
	private static final class Level {
		double value;
		double enforceable;
		long updatedMs;
	}

	/** Both levels of one check after a flag. */
	public record Levels(double total, double enforceable) {
	}

	private final Map<UUID, Map<String, Level>> levels = new ConcurrentHashMap<>();

	/** Applies decay since the last update, adds {@code amount} to the total level only and returns it. */
	public double add(UUID player, String checkId, double amount, double decayPerMinute, long nowMs) {
		return add(player, checkId, amount, false, decayPerMinute, nowMs).total();
	}

	/**
	 * Applies decay since the last update and adds {@code amount} to the total level and, if
	 * {@code enforceable}, to the enforceable level.
	 */
	public Levels add(UUID player, String checkId, double amount, boolean enforceable, double decayPerMinute, long nowMs) {
		Level level = levels.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).computeIfAbsent(checkId, k -> new Level());
		synchronized (level) {
			level.value = decayed(level.value, level.updatedMs, decayPerMinute, nowMs) + amount;
			level.enforceable = decayed(level.enforceable, level.updatedMs, decayPerMinute, nowMs) + (enforceable ? amount : 0);
			level.updatedMs = nowMs;
			return new Levels(level.value, level.enforceable);
		}
	}

	/** The current, decayed level (0 if none). */
	public double get(UUID player, String checkId, double decayPerMinute, long nowMs) {
		Map<String, Level> perPlayer = levels.get(player);
		Level level = perPlayer == null ? null : perPlayer.get(checkId);
		if (level == null) {
			return 0;
		}
		synchronized (level) {
			return decayed(level.value, level.updatedMs, decayPerMinute, nowMs);
		}
	}

	/** The current, decayed enforceable level (0 if none). */
	public double getEnforceable(UUID player, String checkId, double decayPerMinute, long nowMs) {
		Map<String, Level> perPlayer = levels.get(player);
		Level level = perPlayer == null ? null : perPlayer.get(checkId);
		if (level == null) {
			return 0;
		}
		synchronized (level) {
			return decayed(level.enforceable, level.updatedMs, decayPerMinute, nowMs);
		}
	}

	/** Current levels of one player, by check ID; checks that decayed to zero are left out. */
	public Map<String, Double> snapshot(UUID player, double decayPerMinute, long nowMs) {
		Map<String, Double> out = new TreeMap<>();
		Map<String, Level> perPlayer = levels.get(player);
		if (perPlayer != null) {
			perPlayer.forEach((check, level) -> {
				double value;
				synchronized (level) {
					value = decayed(level.value, level.updatedMs, decayPerMinute, nowMs);
				}
				if (value > 0) {
					out.put(check, value);
				}
			});
		}
		return out;
	}

	public void reset(UUID player) {
		levels.remove(player);
	}

	public void reset(UUID player, String checkId) {
		Map<String, Level> perPlayer = levels.get(player);
		if (perPlayer != null) {
			perPlayer.remove(checkId);
		}
	}

	/** Clears one check for everyone, e.g. when it is disabled. */
	public void clearCheck(String checkId) {
		levels.values().forEach(perPlayer -> perPlayer.remove(checkId));
	}

	/** Clears everything, e.g. when the server stops. */
	public void clear() {
		levels.clear();
	}

	public int trackedPlayers() {
		return levels.size();
	}

	private static double decayed(double value, long updatedMs, double decayPerMinute, long nowMs) {
		long elapsed = Math.max(0, nowMs - updatedMs);
		return Math.max(0, value - decayPerMinute * elapsed / 60_000.0);
	}
}
