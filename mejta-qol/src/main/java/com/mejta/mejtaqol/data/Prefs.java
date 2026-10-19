package com.mejta.mejtaqol.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

/** Small per-player preferences ({@code prefs/<uuid>}): currently whether they accept teleport requests. */
public final class Prefs {
	private static final String FOLDER = "prefs";

	private final DataStore store;
	private final Map<UUID, JsonObject> cache = new ConcurrentHashMap<>();

	public Prefs(DataStore store) {
		this.store = store;
	}

	private JsonObject doc(UUID player) {
		return cache.computeIfAbsent(player, id -> store.read(DataStore.playerKey(FOLDER, id)).orElseGet(JsonObject::new));
	}

	public boolean acceptsTeleportRequests(UUID player) {
		JsonObject d = doc(player);
		return !d.has("tpa_enabled") || d.get("tpa_enabled").getAsBoolean();
	}

	public void setAcceptsTeleportRequests(UUID player, boolean accept) {
		JsonObject d = doc(player);
		d.addProperty("tpa_enabled", accept);
		store.write(DataStore.playerKey(FOLDER, player), d);
	}

	public void forget(UUID player) {
		cache.remove(player);
	}

	public void clear() {
		cache.clear();
	}
}
