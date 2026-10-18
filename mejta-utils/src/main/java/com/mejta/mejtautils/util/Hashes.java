package com.mejta.mejtautils.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/** SHA-256 of JSON values, serialized compactly and without HTML escaping. */
public final class Hashes {
	private static final Gson CANONICAL = new GsonBuilder().disableHtmlEscaping().create();

	private Hashes() {
	}

	public static String sha256(JsonElement value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(CANONICAL.toJson(value).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
		}
	}
}
