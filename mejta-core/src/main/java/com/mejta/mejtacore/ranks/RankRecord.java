package com.mejta.mejtacore.ranks;

import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.rank.Rank;

/**
 * A stored rank, {@code mejta-data/mejtacore/store/ranks/<uuid>.json}. {@code source} is {@code staff} (set by a
 * command) or {@code playtime} (earned).
 */
public record RankRecord(Rank rank, String name, String source, long sinceMillis) {
	public static final String STAFF = "staff";
	public static final String PLAYTIME = "playtime";

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("rank", rank.id());
		json.addProperty("name", name);
		json.addProperty("source", source);
		json.addProperty("since", sinceMillis);
		return json;
	}

	/** Empty if the document has no known rank. */
	public static Optional<RankRecord> fromJson(JsonObject json) {
		Optional<Rank> rank = Rank.byId(string(json, "rank"));
		if (rank.isEmpty()) {
			return Optional.empty();
		}
		JsonElement since = json.get("since");
		long millis = since != null && since.isJsonPrimitive() && since.getAsJsonPrimitive().isNumber() ? since.getAsLong() : 0;
		String source = string(json, "source");
		return Optional.of(new RankRecord(rank.get(), string(json, "name"), source.isEmpty() ? STAFF : source, millis));
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	/** Whether a Member with this record (or none) may still earn Bronze by playtime: only if nothing is stored yet. */
	public static boolean mayEarn(Optional<RankRecord> record) {
		return record.isEmpty();
	}

	/** Playtime in ticks at which Bronze is earned; {@code 0} hours: never. */
	public static long earnTicks(int hours) {
		return hours <= 0 ? Long.MAX_VALUE : hours * 72_000L;
	}
}
