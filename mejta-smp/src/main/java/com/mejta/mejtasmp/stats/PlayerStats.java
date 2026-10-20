package com.mejta.mejtasmp.stats;

import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * One player's numbers. Kills, deaths, play time and mob kills come from the game's own statistics;
 * the kill streak is MejtaSMP's (the current one ends at death).
 */
public record PlayerStats(UUID id, String name, int kills, int deaths, long playTicks, int mobKills, int streak, int bestStreak) {
	public static PlayerStats empty(UUID id, String name) {
		return new PlayerStats(id, name, 0, 0, 0, 0, 0, 0);
	}

	public PlayerStats withGameStats(String newName, int newKills, int newDeaths, long newPlayTicks, int newMobKills) {
		return new PlayerStats(id, newName, newKills, newDeaths, newPlayTicks, newMobKills, streak, bestStreak);
	}

	public PlayerStats withPlayTicks(long ticks) {
		return new PlayerStats(id, name, kills, deaths, ticks, mobKills, streak, bestStreak);
	}

	public PlayerStats killed() {
		int next = streak + 1;
		return new PlayerStats(id, name, kills, deaths, playTicks, mobKills, next, Math.max(bestStreak, next));
	}

	public PlayerStats died() {
		return new PlayerStats(id, name, kills, deaths, playTicks, mobKills, 0, bestStreak);
	}

	/** Kills per death, as {@code 1.50}; with no deaths, the kills. */
	public String kd() {
		return String.format(Locale.ROOT, "%.2f", deaths == 0 ? (double) kills : (double) kills / deaths);
	}

	/** {@code 12h 05m}, or {@code 45m} below an hour. */
	public static String formatTicks(long ticks) {
		long minutes = ticks / 20 / 60;
		return minutes < 60 ? minutes + "m" : minutes / 60 + "h " + String.format(Locale.ROOT, "%02dm", minutes % 60);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.addProperty("kills", kills);
		json.addProperty("deaths", deaths);
		json.addProperty("play_ticks", playTicks);
		json.addProperty("mob_kills", mobKills);
		json.addProperty("streak", streak);
		json.addProperty("best_streak", bestStreak);
		return json;
	}

	public static PlayerStats fromJson(UUID id, JsonObject json) {
		return new PlayerStats(id, json.has("name") ? json.get("name").getAsString() : "?",
				intOf(json, "kills"), intOf(json, "deaths"), json.has("play_ticks") ? json.get("play_ticks").getAsLong() : 0,
				intOf(json, "mob_kills"), intOf(json, "streak"), intOf(json, "best_streak"));
	}

	private static int intOf(JsonObject json, String key) {
		return json.has(key) ? Math.max(0, json.get(key).getAsInt()) : 0;
	}
}
