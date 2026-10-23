package com.mejta.mejtaeconomy.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Data store keys for integrated servers (singleplayer, LAN), which can open many worlds in one game
 * session: their accounts live under {@code worlds/<world id>/}, so the money of one world never reaches
 * another. A dedicated server uses the plain key.
 */
public final class WorldKeys {
	private static final int NAME_LENGTH = 24;

	private WorldKeys() {
	}

	/** {@code worlds/<8-digit hash of the path>-<folder name>/<key>}. */
	public static String scoped(String key, String folderName, String absolutePath) {
		String hash;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(absolutePath.getBytes(StandardCharsets.UTF_8));
			hash = HexFormat.of().formatHex(digest, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
		}
		StringBuilder name = new StringBuilder();
		for (char c : folderName.toLowerCase(Locale.ROOT).toCharArray()) {
			if (name.length() >= NAME_LENGTH) {
				break;
			}
			name.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
		}
		return "worlds/" + (name.isEmpty() ? hash : hash + "-" + name) + "/" + key;
	}
}
