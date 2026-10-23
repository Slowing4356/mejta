package com.mejta.mejtacrates.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One prize of a crate. A reward can give items, run console commands and pay money, in any mix; one
 * with none of them is a "nothing" prize. Items are kept as JSON (the game's item codec), so this class
 * needs no running game.
 *
 * @param weight    relative chance; a reward with weight 10 is drawn twice as often as one with 5
 * @param name      shown in the preview and in messages; empty = the display item's own name
 * @param display   the icon in the preview and the animation; empty = the first item, or paper
 * @param items     given to the player (overflow drops at their feet)
 * @param commands  run by the console, with {@code {player}}, {@code {uuid}} and {@code {crate}} replaced
 * @param money     paid through MejtaCore's economy, in minor units (1050 = 10.50 with 2 digits); 0 = none
 * @param broadcast announce wins to everyone: {@code true}, {@code false}, or empty = when the chance is at
 *                  most {@code broadcast.chance_percent}
 */
public record Reward(double weight, String name, Optional<JsonElement> display, List<JsonElement> items, List<String> commands, long money,
		Optional<Boolean> broadcast) {
	public static final double MAX_WEIGHT = 1_000_000;

	public Reward {
		if (!validWeight(weight)) {
			throw new IllegalArgumentException("weight must be above 0 and at most " + MAX_WEIGHT + ": " + weight);
		}
		if (money < 0) {
			throw new IllegalArgumentException("money must not be negative");
		}
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(display, "display");
		Objects.requireNonNull(broadcast, "broadcast");
		items = items.stream().map(JsonElement::deepCopy).toList();
		commands = List.copyOf(commands);
		display = display.map(JsonElement::deepCopy);
	}

	public static boolean validWeight(double weight) {
		return Double.isFinite(weight) && weight > 0 && weight <= MAX_WEIGHT;
	}

	public static Reward ofItem(double weight, JsonElement item) {
		return new Reward(weight, "", Optional.empty(), List.of(item), List.of(), 0, Optional.empty());
	}

	public static Reward ofCommand(double weight, String command, Optional<JsonElement> display) {
		return new Reward(weight, "", display, List.of(), List.of(command), 0, Optional.empty());
	}

	public static Reward ofMoney(double weight, long money) {
		return new Reward(weight, "", Optional.empty(), List.of(), List.of(), money, Optional.empty());
	}

	public boolean paysMoney() {
		return money > 0;
	}

	public Reward withWeight(double newWeight) {
		return new Reward(newWeight, name, display, items, commands, money, broadcast);
	}

	public Reward withName(String newName) {
		return new Reward(weight, newName, display, items, commands, money, broadcast);
	}

	public Reward withDisplay(Optional<JsonElement> newDisplay) {
		return new Reward(weight, name, newDisplay, items, commands, money, broadcast);
	}

	public Reward withBroadcast(Optional<Boolean> newBroadcast) {
		return new Reward(weight, name, display, items, commands, money, newBroadcast);
	}

	public Reward withCommand(String command) {
		List<String> more = new ArrayList<>(commands);
		more.add(command);
		return new Reward(weight, name, display, items, more, money, broadcast);
	}

	public Reward withItem(JsonElement item) {
		List<JsonElement> more = new ArrayList<>(items);
		more.add(item);
		return new Reward(weight, name, display, more, commands, money, broadcast);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("weight", weight);
		if (!name.isEmpty()) {
			json.addProperty("name", name);
		}
		display.ifPresent(d -> json.add("display", d.deepCopy()));
		if (!items.isEmpty()) {
			JsonArray array = new JsonArray();
			items.forEach(i -> array.add(i.deepCopy()));
			json.add("items", array);
		}
		if (!commands.isEmpty()) {
			JsonArray array = new JsonArray();
			commands.forEach(array::add);
			json.add("commands", array);
		}
		if (money > 0) {
			json.addProperty("money", money);
		}
		broadcast.ifPresent(b -> json.addProperty("broadcast", b));
		return json;
	}

	/** @throws IllegalArgumentException if the JSON is not a valid reward */
	public static Reward fromJson(JsonObject json) {
		double weight = number(json, "weight").map(JsonPrimitive::getAsDouble)
				.orElseThrow(() -> new IllegalArgumentException("reward without a weight"));
		String name = json.has("name") ? json.get("name").getAsString() : "";
		Optional<JsonElement> display = json.has("display") && json.get("display").isJsonObject()
				? Optional.of(json.get("display")) : Optional.empty();
		List<JsonElement> items = new ArrayList<>();
		if (json.has("items") && json.get("items").isJsonArray()) {
			json.getAsJsonArray("items").forEach(items::add);
		}
		List<String> commands = new ArrayList<>();
		if (json.has("commands") && json.get("commands").isJsonArray()) {
			for (JsonElement c : json.getAsJsonArray("commands")) {
				String text = c.getAsString().strip();
				if (!text.isEmpty()) {
					commands.add(text.startsWith("/") ? text.substring(1) : text);
				}
			}
		}
		long money = number(json, "money").map(JsonPrimitive::getAsLong).orElse(0L);
		Optional<Boolean> broadcast = json.has("broadcast") && json.get("broadcast").isJsonPrimitive()
				? Optional.of(json.get("broadcast").getAsBoolean()) : Optional.empty();
		return new Reward(weight, name, display, items, commands, money, broadcast);
	}

	private static Optional<JsonPrimitive> number(JsonObject json, String key) {
		JsonElement e = json.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Optional.of(e.getAsJsonPrimitive()) : Optional.empty();
	}
}
