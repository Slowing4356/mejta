package com.mejta.mejtaqol.home;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.util.Names;

/** One player's homes, sorted by name. Not thread-safe; used on the server thread. */
public final class HomeBook {
	public enum SetResult {
		CREATED,
		MOVED,
		INVALID_NAME,
		LIMIT_REACHED
	}

	private final Map<String, Location> homes = new TreeMap<>();

	public Map<String, Location> all() {
		return Collections.unmodifiableMap(homes);
	}

	public int size() {
		return homes.size();
	}

	public Optional<Location> get(String name) {
		return Names.normalize(name).map(homes::get);
	}

	/**
	 * Creates or moves a home. Moving an existing home is always allowed, even above the limit (for
	 * example after the limit was lowered); creating one needs room below {@code max}.
	 */
	public SetResult set(String name, Location location, int max) {
		Optional<String> n = Names.normalize(name);
		if (n.isEmpty()) {
			return SetResult.INVALID_NAME;
		}
		if (homes.containsKey(n.get())) {
			homes.put(n.get(), location);
			return SetResult.MOVED;
		}
		if (homes.size() >= max) {
			return SetResult.LIMIT_REACHED;
		}
		homes.put(n.get(), location);
		return SetResult.CREATED;
	}

	public boolean delete(String name) {
		return Names.normalize(name).map(n -> homes.remove(n) != null).orElse(false);
	}

	/**
	 * The home {@code /home} without a name uses: the one called {@code defaultName}, else the only
	 * home if there is exactly one.
	 */
	public Optional<Map.Entry<String, Location>> defaultHome(String defaultName) {
		Location named = homes.get(defaultName);
		if (named != null) {
			return Optional.of(Map.entry(defaultName, named));
		}
		if (homes.size() == 1) {
			return Optional.of(homes.entrySet().iterator().next());
		}
		return Optional.empty();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject list = new JsonObject();
		homes.forEach((name, loc) -> list.add(name, loc.toJson()));
		o.add("homes", list);
		return o;
	}

	/** Reads a stored book; entries with an invalid name or location are skipped. */
	public static HomeBook fromJson(JsonObject json) {
		HomeBook book = new HomeBook();
		if (json != null && json.has("homes") && json.get("homes").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("homes").entrySet()) {
				Optional<String> name = Names.normalize(e.getKey());
				Optional<Location> loc = Location.fromJson(e.getValue());
				if (name.isPresent() && loc.isPresent()) {
					book.homes.put(name.get(), loc.get());
				}
			}
		}
		return book;
	}
}
