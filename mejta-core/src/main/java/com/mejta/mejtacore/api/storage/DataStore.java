package com.mejta.mejtacore.api.storage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/**
 * Small, persistent JSON documents of one addon, addressed by key: vanish state, a frozen player, a
 * saved inventory. Obtain it with {@code MejtaCoreAPI.dataStore(addonId)}.
 *
 * <p>Each key is one file, {@code mejta-data/<addon>/store/<key>.json}. Writes replace the whole file
 * atomically (never half-written), and the previous versions are kept as {@code .bak1} to
 * {@code .bak3}. If a file is found corrupt, it is renamed to {@code .broken-<millis>} and the
 * newest readable backup is returned instead; a missing file is simply absent.
 *
 * <p>Keys are validated: lowercase letters, digits, {@code _}, {@code -} and {@code .}, in
 * {@code /}-separated segments that start with a letter or digit, at most 128 characters. They can
 * never leave the addon's folder. Never build a key from unchecked player input; use
 * {@link #playerKey(String, UUID)} for per-player data.
 *
 * <p>{@link #read} sees every earlier {@link #write} and {@link #delete} at once, even while they are
 * still queued. All methods are thread-safe. Values are copied on the way in and out.
 *
 * @since API version 4
 */
public interface DataStore {
	Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]*(/[a-z0-9][a-z0-9_.-]*)*");
	int MAX_KEY_LENGTH = 128;

	/** The stored document, or empty if none. Reads from disk on the calling thread (files are small). */
	Optional<JsonObject> read(String key);

	/** Queues the document for writing on MejtaCore's I/O thread and returns at once. */
	void write(String key, JsonObject value);

	/** Like {@link #write}; the future completes once the document is on the storage device. */
	CompletableFuture<Void> writeDurable(String key, JsonObject value);

	/**
	 * Writes the document on the calling thread and forces it to the storage device before returning.
	 * Use it for write-ahead records that must exist before you change the game, e.g. a saved
	 * inventory before clearing it. Waits for earlier queued writes of the same key first.
	 *
	 * @throws IOException if the document could not be written; nothing was changed then
	 */
	void writeNow(String key, JsonObject value) throws IOException;

	/** Queues removal of the document and its backups. */
	void delete(String key);

	/** Keys directly inside {@code folder} (e.g. {@code "frozen"}), sorted. {@code ""} lists the top level. */
	List<String> keys(String folder);

	/** Completes once every write and delete queued so far is done. */
	CompletableFuture<Void> flush();

	/** {@code prefix + "/" + uuid}, with the UUID in canonical lowercase form. */
	static String playerKey(String prefix, UUID player) {
		return prefix + "/" + player.toString().toLowerCase(java.util.Locale.ROOT);
	}

	/** Whether {@code key} is a valid key. */
	static boolean isValidKey(String key) {
		return key != null && key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
	}
}
