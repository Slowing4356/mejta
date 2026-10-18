package com.mejta.mejtacore.config;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.mejta.mejtacore.api.config.ConfigData;
import com.mejta.mejtacore.api.config.ConfigSpec;

/** {@link ConfigData} view over the raw JSON object, used during migrations. */
final class JsonConfigData implements ConfigData {
	private final JsonObject json;
	private final int version;

	JsonConfigData(JsonObject json, int version) {
		this.json = json;
		this.version = version;
	}

	@Override
	public int version() {
		return version;
	}

	@Override
	public Set<String> keys() {
		Set<String> keys = new LinkedHashSet<>(json.keySet());
		keys.remove(ConfigSpec.VERSION_KEY);
		return keys;
	}

	@Override
	public boolean has(String key) {
		return !key.equals(ConfigSpec.VERSION_KEY) && json.has(key);
	}

	@Override
	public Optional<Object> get(String key) {
		JsonElement element = has(key) ? json.get(key) : null;
		if (element == null || !element.isJsonPrimitive()) {
			return Optional.empty();
		}
		JsonPrimitive p = element.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return Optional.of(p.getAsBoolean());
		}
		if (p.isNumber()) {
			return Optional.of(p.getAsNumber());
		}
		return Optional.of(p.getAsString());
	}

	@Override
	public void set(String key, Object value) {
		checkKey(key);
		switch (value) {
			case Boolean b -> json.addProperty(key, b);
			case Number n -> json.addProperty(key, n);
			case String s -> json.addProperty(key, s);
			default -> throw new IllegalArgumentException("Unsupported value type " + value.getClass().getName());
		}
	}

	@Override
	public void rename(String from, String to) {
		checkKey(from);
		checkKey(to);
		JsonElement value = json.remove(from);
		if (value != null) {
			json.add(to, value);
		}
	}

	@Override
	public void remove(String key) {
		checkKey(key);
		json.remove(key);
	}

	private static void checkKey(String key) {
		if (key.equals(ConfigSpec.VERSION_KEY)) {
			throw new IllegalArgumentException("'" + key + "' is managed by MejtaCore");
		}
	}
}
