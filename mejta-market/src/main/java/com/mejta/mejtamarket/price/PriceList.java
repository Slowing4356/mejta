package com.mejta.mejtamarket.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The admin's price list ({@code config/mejta/mejtamarket-prices.json}): a starting price per item, in
 * whole units of the currency. Only items on the list are traded by the bot, and their reference prices
 * start here. Kept in whole units in the file so it stays readable whatever the currency's decimals.
 */
public final class PriceList {
	/** Common goods that every survival server produces. Rare items (elytra, netherite, spawners) are left out on purpose. */
	public static final Map<String, Double> DEFAULTS = defaults();

	private final Map<String, BigDecimal> prices = new LinkedHashMap<>();

	public PriceList() {
	}

	public static PriceList withDefaults() {
		PriceList list = new PriceList();
		DEFAULTS.forEach((id, price) -> list.prices.put(id, BigDecimal.valueOf(price)));
		return list;
	}

	/** The listed price in minor units, or empty if the item is not on the list. */
	public OptionalLong price(String item, int fractionDigits) {
		BigDecimal p = prices.get(item);
		if (p == null) {
			return OptionalLong.empty();
		}
		long minor = p.movePointRight(fractionDigits).setScale(0, RoundingMode.HALF_UP).longValue();
		return minor > 0 ? OptionalLong.of(minor) : OptionalLong.empty();
	}

	public boolean contains(String item) {
		return prices.containsKey(item);
	}

	/** Sets an item's price in whole units, e.g. {@code 12.5}. */
	public void set(String item, BigDecimal price) {
		if (price.signum() <= 0) {
			throw new IllegalArgumentException("price must be positive");
		}
		prices.put(item, price.stripTrailingZeros());
	}

	public boolean remove(String item) {
		return prices.remove(item) != null;
	}

	public List<String> items() {
		List<String> ids = new ArrayList<>(prices.keySet());
		Collections.sort(ids);
		return ids;
	}

	public int size() {
		return prices.size();
	}

	/** The listed price in whole units, for messages and the file. */
	public BigDecimal wholeUnits(String item) {
		return prices.get(item);
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("_comment", "Prices in whole units of the currency. The market bot only trades items listed here, "
				+ "and their reference prices start at these values. Reload with /mejtaadmin mejtamarket prices reload.");
		JsonObject items = new JsonObject();
		for (String id : items()) {
			items.add(id, new JsonPrimitive(prices.get(id)));
		}
		root.add("items", items);
		return root;
	}

	/**
	 * Reads the file's JSON. Entries that are not positive numbers are skipped and named in {@code problems}.
	 */
	public static PriceList fromJson(JsonObject root, List<String> problems) {
		PriceList list = new PriceList();
		if (!root.has("items") || !root.get("items").isJsonObject()) {
			problems.add("no \"items\" object");
			return list;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
			try {
				BigDecimal price = e.getValue().getAsBigDecimal();
				if (price.signum() <= 0 || !e.getKey().contains(":")) {
					problems.add(e.getKey());
					continue;
				}
				list.prices.put(e.getKey(), price.stripTrailingZeros());
			} catch (RuntimeException ex) {
				problems.add(e.getKey());
			}
		}
		return list;
	}

	private static Map<String, Double> defaults() {
		Map<String, Double> m = new LinkedHashMap<>();
		// Building blocks
		m.put("minecraft:cobblestone", 0.5);
		m.put("minecraft:stone", 1.0);
		m.put("minecraft:dirt", 0.25);
		m.put("minecraft:sand", 1.0);
		m.put("minecraft:gravel", 1.0);
		m.put("minecraft:glass", 2.0);
		m.put("minecraft:oak_log", 2.0);
		m.put("minecraft:spruce_log", 2.0);
		m.put("minecraft:birch_log", 2.0);
		m.put("minecraft:obsidian", 15.0);
		m.put("minecraft:torch", 0.5);
		// Ores and minerals
		m.put("minecraft:coal", 4.0);
		m.put("minecraft:copper_ingot", 3.0);
		m.put("minecraft:iron_ingot", 12.0);
		m.put("minecraft:gold_ingot", 20.0);
		m.put("minecraft:redstone", 3.0);
		m.put("minecraft:lapis_lazuli", 5.0);
		m.put("minecraft:quartz", 4.0);
		m.put("minecraft:amethyst_shard", 6.0);
		m.put("minecraft:emerald", 40.0);
		m.put("minecraft:diamond", 100.0);
		m.put("minecraft:glowstone_dust", 4.0);
		// Farming and food
		m.put("minecraft:wheat", 1.5);
		m.put("minecraft:carrot", 1.5);
		m.put("minecraft:potato", 1.5);
		m.put("minecraft:sugar_cane", 2.0);
		m.put("minecraft:pumpkin", 3.0);
		m.put("minecraft:melon_slice", 0.5);
		m.put("minecraft:cactus", 2.0);
		m.put("minecraft:bamboo", 0.5);
		m.put("minecraft:kelp", 0.5);
		m.put("minecraft:nether_wart", 3.0);
		m.put("minecraft:apple", 3.0);
		m.put("minecraft:bread", 4.0);
		m.put("minecraft:cooked_beef", 5.0);
		m.put("minecraft:egg", 1.0);
		m.put("minecraft:honey_bottle", 6.0);
		// Mob drops
		m.put("minecraft:bone", 2.0);
		m.put("minecraft:rotten_flesh", 0.5);
		m.put("minecraft:string", 2.0);
		m.put("minecraft:gunpowder", 5.0);
		m.put("minecraft:spider_eye", 3.0);
		m.put("minecraft:ender_pearl", 15.0);
		m.put("minecraft:blaze_rod", 20.0);
		m.put("minecraft:slime_ball", 8.0);
		m.put("minecraft:leather", 4.0);
		m.put("minecraft:feather", 1.5);
		m.put("minecraft:ink_sac", 2.0);
		m.put("minecraft:arrow", 1.0);
		m.put("minecraft:experience_bottle", 10.0);
		return Collections.unmodifiableMap(m);
	}
}
