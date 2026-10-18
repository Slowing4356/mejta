package com.mejta.mejtaac.violation;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Violation levels per player and check, decaying linearly over time. Memory is bounded by
 * online players times checks: entries are removed when a player disconnects or decays to zero.
 * Thread-safe; time is passed in so the logic can be tested deterministically.
 */
public final class ViolationTracker {
	private static final class Level {
		double value;
		long updatedMs;
	}

	private final Map<UUID, Map<String, Level>> levels = new ConcurrentHashMap<>();

	/** Applies decay since the last update, adds {@code amount} and returns the new level. */
	public double add(UUID player, String checkId, double amount, double decayPerMinute, long nowMs) {
		Level level = levels.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).computeIfAbsent(checkId, k -> new Level());
		synchronized (level) {
			level.value = decayed(level, decayPerMinute, nowMs) + amount;
			level.updatedMs = nowMs;
			return level.value;
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
			return decayed(level, decayPerMinute, nowMs);
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
					value = decayed(level, decayPerMinute, nowMs);
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

	public int trackedPlayers() {
		return levels.size();
	}

	private static double decayed(Level level, double decayPerMinute, long nowMs) {
		long elapsed = Math.max(0, nowMs - level.updatedMs);
		return Math.max(0, level.value - decayPerMinute * elapsed / 60_000.0);
	}
}
