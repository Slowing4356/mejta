package com.mejta.mejtautils.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * MejtaUtils' view of its data store, optionally limited to one world.
 *
 * <p>MejtaCore's data store lives in {@code <game dir>/mejta-data}, shared by every world the game
 * opens. A dedicated server only ever runs one world, so it uses the keys as they are (unscoped, the
 * layout of every earlier release). An integrated server (singleplayer, LAN) can open many worlds in
 * one game session: there every key is put under {@code worlds/<world id>/}, so staff-mode sessions,
 * freezes, vanish state and queued offline changes of one world never reach another.
 *
 * <p>The scope is switched only while no server runs (at server start and stop). Thread-safe.
 */
public final class ScopedDataStore implements DataStore {
	static final String WORLDS = "worlds";
	private static final int NAME_LENGTH = 24;

	private final DataStore delegate;
	/** {@code ""} (unscoped) or {@code "worlds/<id>/"}. */
	private volatile String prefix = "";

	public ScopedDataStore(DataStore delegate) {
		this.delegate = delegate;
	}

	/**
	 * A key segment naming one world: an 8-digit hash of the world folder's full path (unique per
	 * folder), then the folder name made key-safe (readable). Moving or renaming the world folder
	 * gives it a new id.
	 */
	public static String worldId(String folderName, String absolutePath) {
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
		return name.isEmpty() ? hash : hash + "-" + name;
	}

	/** Puts every key under {@code worlds/<worldId>/} (integrated servers). */
	public void scopeToWorld(String worldId) {
		String scoped = WORLDS + "/" + worldId;
		if (!DataStore.isValidKey(scoped)) {
			throw new IllegalArgumentException("invalid world id: " + worldId);
		}
		prefix = scoped + "/";
	}

	/** Uses the keys as they are (dedicated servers, and between two integrated servers). */
	public void unscope() {
		prefix = "";
	}

	/** {@code ""} or {@code "worlds/<id>/"}: for log messages. */
	public String scope() {
		return prefix;
	}

	private String scoped(String key) {
		return prefix + key;
	}

	@Override
	public Optional<JsonObject> read(String key) {
		return delegate.read(scoped(key));
	}

	@Override
	public void write(String key, JsonObject value) {
		delegate.write(scoped(key), value);
	}

	@Override
	public CompletableFuture<Void> writeDurable(String key, JsonObject value) {
		return delegate.writeDurable(scoped(key), value);
	}

	@Override
	public void writeNow(String key, JsonObject value) throws IOException {
		delegate.writeNow(scoped(key), value);
	}

	@Override
	public void delete(String key) {
		delegate.delete(scoped(key));
	}

	@Override
	public List<String> keys(String folder) {
		String p = prefix;
		String inner = folder.isEmpty() ? (p.isEmpty() ? "" : p.substring(0, p.length() - 1)) : p + folder;
		List<String> out = new ArrayList<>();
		for (String key : delegate.keys(inner)) {
			if (key.startsWith(p)) {
				out.add(key.substring(p.length()));
			}
		}
		return out;
	}

	@Override
	public CompletableFuture<Void> flush() {
		return delegate.flush();
	}
}
