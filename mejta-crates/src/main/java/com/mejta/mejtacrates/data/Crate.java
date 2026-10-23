package com.mejta.mejtacrates.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A crate: its name, icon, key item and rewards. Stored as {@code mejta-data/mejtacrates/store/crates/<id>.json}.
 *
 * @param id      lowercase letters, digits, {@code _} and {@code -}; at most 32 characters; never changes
 * @param name    shown to players, e.g. {@code Rare Crate}
 * @param color   a Minecraft colour name for the name, e.g. {@code GOLD}
 * @param icon    the icon in the crate list; empty = a chest
 * @param keyItem the item physical keys are made of; empty = a tripwire hook
 * @param rewards the prizes, in the order the preview shows them
 */
public record Crate(String id, String name, String color, Optional<JsonElement> icon, Optional<JsonElement> keyItem, List<Reward> rewards) {
	public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
	public static final int MAX_REWARDS = 270;
	public static final int MAX_NAME = 48;

	public Crate {
		if (!isValidId(id)) {
			throw new IllegalArgumentException("Invalid crate ID '" + id + "'");
		}
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(color, "color");
		name = name.isBlank() ? id : limit(name.strip());
		color = color.toUpperCase(Locale.ROOT);
		icon = icon.map(JsonElement::deepCopy);
		keyItem = keyItem.map(JsonElement::deepCopy);
		rewards = List.copyOf(rewards);
		if (rewards.size() > MAX_REWARDS) {
			throw new IllegalArgumentException("A crate holds at most " + MAX_REWARDS + " rewards");
		}
	}

	public static boolean isValidId(String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	public static Crate create(String id, String name) {
		return new Crate(id, name, "GOLD", Optional.empty(), Optional.empty(), List.of());
	}

	/** The rewards that can be drawn now, e.g. without money rewards when there is no economy. */
	public List<Reward> eligible(Predicate<Reward> available) {
		return rewards.stream().filter(available).toList();
	}

	public Crate withName(String newName) {
		return new Crate(id, newName, color, icon, keyItem, rewards);
	}

	public Crate withColor(String newColor) {
		return new Crate(id, name, newColor, icon, keyItem, rewards);
	}

	public Crate withIcon(Optional<JsonElement> newIcon) {
		return new Crate(id, name, color, newIcon, keyItem, rewards);
	}

	public Crate withKeyItem(Optional<JsonElement> newKeyItem) {
		return new Crate(id, name, color, icon, newKeyItem, rewards);
	}

	public Crate withReward(Reward reward) {
		List<Reward> more = new ArrayList<>(rewards);
		more.add(reward);
		return new Crate(id, name, color, icon, keyItem, more);
	}

	/** @param index 0-based */
	public Crate withReward(int index, Reward reward) {
		List<Reward> changed = new ArrayList<>(rewards);
		changed.set(index, reward);
		return new Crate(id, name, color, icon, keyItem, changed);
	}

	/** @param index 0-based */
	public Crate withoutReward(int index) {
		List<Reward> fewer = new ArrayList<>(rewards);
		fewer.remove(index);
		return new Crate(id, name, color, icon, keyItem, fewer);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.addProperty("color", color);
		icon.ifPresent(i -> json.add("icon", i.deepCopy()));
		keyItem.ifPresent(k -> json.add("key_item", k.deepCopy()));
		JsonArray array = new JsonArray();
		rewards.forEach(r -> array.add(r.toJson()));
		json.add("rewards", array);
		return json;
	}

	/**
	 * Reads a crate. Rewards that are not valid are left out and named in {@code problems}.
	 *
	 * @throws IllegalArgumentException if the ID is invalid
	 */
	public static Crate fromJson(String id, JsonObject json, List<String> problems) {
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String color = json.has("color") ? json.get("color").getAsString() : "GOLD";
		Optional<JsonElement> icon = json.has("icon") && json.get("icon").isJsonObject() ? Optional.of(json.get("icon")) : Optional.empty();
		Optional<JsonElement> key = json.has("key_item") && json.get("key_item").isJsonObject() ? Optional.of(json.get("key_item")) : Optional.empty();
		List<Reward> rewards = new ArrayList<>();
		if (json.has("rewards") && json.get("rewards").isJsonArray()) {
			int n = 0;
			for (JsonElement e : json.getAsJsonArray("rewards")) {
				n++;
				try {
					if (!e.isJsonObject()) {
						throw new IllegalArgumentException("not an object");
					}
					if (rewards.size() < MAX_REWARDS) {
						rewards.add(Reward.fromJson(e.getAsJsonObject()));
					}
				} catch (RuntimeException ex) {
					problems.add("crate " + id + ", reward " + n + ": " + ex.getMessage());
				}
			}
		}
		return new Crate(id, name, color, icon, key, rewards);
	}

	private static String limit(String text) {
		return text.length() <= MAX_NAME ? text : text.substring(0, MAX_NAME);
	}
}
