package com.mejta.mejtamarket.data;

import java.util.UUID;

import com.google.gson.JsonObject;

/** Optional fields of stored documents. */
final class Json {
	private Json() {
	}

	static String string(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	static UUID uuid(JsonObject o, String key) {
		String s = string(o, key);
		return s == null ? null : UUID.fromString(s);
	}
}
