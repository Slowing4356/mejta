package com.mejta.mejtaqol.warp;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.util.Names;

/** The server's warps, sorted by name. Not thread-safe; used on the server thread. */
public final class WarpBook {
	/**
	 * @param icon    item ID shown in the warps menu, e.g. {@code minecraft:ender_pearl}
	 * @param creator name of who set it, for the menu
	 */
	public record Warp(String name, Location location, String icon, String creator) {
	}

	public static final String DEFAULT_ICON = "minecraft:ender_pearl";

	private final Map<String, Warp> warps = new TreeMap<>();

	public Map<String, Warp> all() {
		return Collections.unmodifiableMap(warps);
	}

	public Optional<Warp> get(String name) {
		return Names.normalize(name).map(warps::get);
	}

	/** Creates or replaces a warp. Returns false for an invalid name. */
	public boolean set(String name, Location location, String icon, String creator) {
		Optional<String> n = Names.normalize(name);
		if (n.isEmpty()) {
			return false;
		}
		warps.put(n.get(), new Warp(n.get(), location, icon == null || icon.isBlank() ? DEFAULT_ICON : icon, creator == null ? "" : creator));
		return true;
	}

	public boolean delete(String name) {
		return Names.normalize(name).map(n -> warps.remove(n) != null).orElse(false);
	}

	public int size() {
		return warps.size();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		JsonObject list = new JsonObject();
		warps.forEach((name, w) -> {
			JsonObject e = w.location().toJson();
			e.addProperty("icon", w.icon());
			e.addProperty("creator", w.creator());
			list.add(name, e);
		});
		o.add("warps", list);
		return o;
	}

	public static WarpBook fromJson(JsonObject json) {
		WarpBook book = new WarpBook();
		if (json != null && json.has("warps") && json.get("warps").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("warps").entrySet()) {
				Optional<Location> loc = Location.fromJson(e.getValue());
				if (loc.isEmpty()) {
					continue;
				}
				JsonObject o = e.getValue().getAsJsonObject();
				String icon = o.has("icon") && o.get("icon").isJsonPrimitive() ? o.get("icon").getAsString() : DEFAULT_ICON;
				String creator = o.has("creator") && o.get("creator").isJsonPrimitive() ? o.get("creator").getAsString() : "";
				book.set(e.getKey(), loc.get(), icon, creator);
			}
		}
		return book;
	}
}
