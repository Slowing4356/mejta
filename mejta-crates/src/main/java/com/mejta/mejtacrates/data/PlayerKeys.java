package com.mejta.mejtacrates.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One player's crate data, {@code players/<uuid>}: their virtual keys, physical keys waiting to be handed
 * out (given while they were offline), and openings whose reward is not handed out yet.
 *
 * <p>An opening is written here, with the key already taken, before its animation starts; the reward is
 * handed out only after the opening is removed again. So a reward is given at most once, and an opening
 * cut short by a disconnect or a crash is finished at the player's next join.
 *
 * @param name     the player's last known name
 * @param keys     crate ID to number of virtual keys (only positive counts)
 * @param physical crate ID to number of physical keys to give at the next join
 * @param pending  openings whose reward has not been handed out
 * @param claimed  tokens of keys given by name (before the first join) that were already added here, newest last
 */
public record PlayerKeys(String name, Map<String, Integer> keys, Map<String, Integer> physical, List<Opening> pending, List<String> claimed) {
	/** How many claimed tokens are remembered. */
	public static final int MAX_CLAIMED = 50;

	/** At most this many keys of one crate per player, so counts never overflow. */
	public static final int MAX_KEYS = 1_000_000;

	/**
	 * @param id       unique, e.g. {@code 1a2b3c4d5e6f}
	 * @param crate    the crate's ID
	 * @param reward   the drawn reward as it was when drawn (later edits of the crate do not change it)
	 * @param key      {@code virtual}, or {@code physical:<serial>}
	 */
	public record Opening(String id, String crate, String crateName, Reward reward, long millis, String key) {
		public Opening {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(crate, "crate");
			Objects.requireNonNull(crateName, "crateName");
			Objects.requireNonNull(reward, "reward");
			Objects.requireNonNull(key, "key");
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("id", id);
			json.addProperty("crate", crate);
			json.addProperty("crate_name", crateName);
			json.add("reward", reward.toJson());
			json.addProperty("millis", millis);
			json.addProperty("key", key);
			return json;
		}

		static Opening fromJson(JsonObject json) {
			return new Opening(json.get("id").getAsString(), json.get("crate").getAsString(),
					json.has("crate_name") ? json.get("crate_name").getAsString() : json.get("crate").getAsString(),
					Reward.fromJson(json.getAsJsonObject("reward")), json.has("millis") ? json.get("millis").getAsLong() : 0,
					json.has("key") ? json.get("key").getAsString() : "virtual");
		}
	}

	public PlayerKeys {
		Objects.requireNonNull(name, "name");
		keys = positive(keys);
		physical = positive(physical);
		pending = List.copyOf(pending);
		claimed = List.copyOf(claimed.size() > MAX_CLAIMED ? claimed.subList(claimed.size() - MAX_CLAIMED, claimed.size()) : claimed);
	}

	public static PlayerKeys empty(String name) {
		return new PlayerKeys(name, Map.of(), Map.of(), List.of(), List.of());
	}

	public int keys(String crate) {
		return keys.getOrDefault(crate, 0);
	}

	public int totalKeys() {
		return keys.values().stream().mapToInt(Integer::intValue).sum();
	}

	public PlayerKeys withName(String newName) {
		return new PlayerKeys(newName, keys, physical, pending, claimed);
	}

	/** Adds (or with a negative {@code delta} removes) virtual keys, never below 0 or above {@link #MAX_KEYS}. */
	public PlayerKeys addKeys(String crate, int delta) {
		Map<String, Integer> changed = new TreeMap<>(keys);
		changed.put(crate, clamp((long) keys(crate) + delta));
		return new PlayerKeys(name, changed, physical, pending, claimed);
	}

	public PlayerKeys addPhysical(String crate, int delta) {
		Map<String, Integer> changed = new TreeMap<>(physical);
		changed.put(crate, clamp((long) physical.getOrDefault(crate, 0) + delta));
		return new PlayerKeys(name, keys, changed, pending, claimed);
	}

	public PlayerKeys withoutPhysical() {
		return new PlayerKeys(name, keys, Map.of(), pending, claimed);
	}

	public PlayerKeys withOpening(Opening opening) {
		List<Opening> more = new ArrayList<>(pending);
		more.add(opening);
		return new PlayerKeys(name, keys, physical, more, claimed);
	}

	public PlayerKeys withoutOpening(String openingId) {
		return new PlayerKeys(name, keys, physical, pending.stream().filter(o -> !o.id().equals(openingId)).toList(), claimed);
	}

	public java.util.Optional<Opening> opening(String openingId) {
		return pending.stream().filter(o -> o.id().equals(openingId)).findFirst();
	}

	public boolean hasClaimed(String token) {
		return claimed.contains(token);
	}

	/**
	 * Adds the keys of {@code other} (keys given by name before the player first joined) and remembers
	 * {@code token}, so the same keys are never added twice.
	 */
	public PlayerKeys merge(PlayerKeys other, String token) {
		List<String> tokens = new ArrayList<>(claimed);
		tokens.add(token);
		PlayerKeys out = new PlayerKeys(name, keys, physical, pending, tokens);
		for (Map.Entry<String, Integer> e : other.keys.entrySet()) {
			out = out.addKeys(e.getKey(), e.getValue());
		}
		for (Map.Entry<String, Integer> e : other.physical.entrySet()) {
			out = out.addPhysical(e.getKey(), e.getValue());
		}
		return out;
	}

	public boolean isEmpty() {
		return keys.isEmpty() && physical.isEmpty() && pending.isEmpty();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.add("keys", counts(keys));
		json.add("physical", counts(physical));
		JsonArray array = new JsonArray();
		pending.forEach(o -> array.add(o.toJson()));
		json.add("pending", array);
		JsonArray tokens = new JsonArray();
		claimed.forEach(tokens::add);
		json.add("claimed", tokens);
		return json;
	}

	/** Openings that cannot be read are dropped and named in {@code problems}. */
	public static PlayerKeys fromJson(JsonObject json, List<String> problems) {
		List<Opening> pending = new ArrayList<>();
		if (json.has("pending") && json.get("pending").isJsonArray()) {
			for (JsonElement e : json.getAsJsonArray("pending")) {
				try {
					pending.add(Opening.fromJson(e.getAsJsonObject()));
				} catch (RuntimeException ex) {
					problems.add("unreadable pending opening " + e + ": " + ex.getMessage());
				}
			}
		}
		List<String> claimed = new ArrayList<>();
		if (json.has("claimed") && json.get("claimed").isJsonArray()) {
			json.getAsJsonArray("claimed").forEach(t -> claimed.add(t.getAsString()));
		}
		return new PlayerKeys(json.has("name") ? json.get("name").getAsString() : "", counts(json, "keys"), counts(json, "physical"), pending,
				claimed);
	}

	private static JsonObject counts(Map<String, Integer> counts) {
		JsonObject json = new JsonObject();
		counts.forEach((k, v) -> json.addProperty(k, v));
		return json;
	}

	private static Map<String, Integer> counts(JsonObject json, String key) {
		Map<String, Integer> out = new TreeMap<>();
		if (json.has(key) && json.get(key).isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : json.getAsJsonObject(key).entrySet()) {
				if (Crate.isValidId(e.getKey()) && e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
					out.put(e.getKey(), clamp(e.getValue().getAsLong()));
				}
			}
		}
		return out;
	}

	private static Map<String, Integer> positive(Map<String, Integer> counts) {
		Map<String, Integer> out = new TreeMap<>();
		counts.forEach((k, v) -> {
			if (v != null && v > 0) {
				out.put(k, v);
			}
		});
		return java.util.Collections.unmodifiableMap(out);
	}

	private static int clamp(long value) {
		return (int) Math.max(0, Math.min(MAX_KEYS, value));
	}
}
