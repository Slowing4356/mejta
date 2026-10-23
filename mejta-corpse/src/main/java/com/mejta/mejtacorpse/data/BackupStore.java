package com.mejta.mejtacorpse.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * Every player's death backups, in the data store under {@code backups/<owner uuid>/}. Nothing is kept in
 * memory: a player's backups are read when they die or when staff look at them, which is rare.
 *
 * <p>Each key ends in {@code <created millis>-<corpse id>}, with the time zero-padded, so the folder's
 * sorted listing is oldest first and pruning needs no reads.
 */
public final class BackupStore {
	static final String FOLDER = "backups";

	private final DataStore store;

	public BackupStore(DataStore store) {
		this.store = store;
	}

	public static String folder(UUID owner) {
		return DataStore.playerKey(FOLDER, owner);
	}

	public static String key(UUID owner, long createdMillis, String id) {
		return folder(owner) + "/" + String.format(Locale.ROOT, "%013d", Math.max(0, createdMillis)) + "-" + id;
	}

	public static String key(DeathBackup backup) {
		return key(backup.owner(), backup.createdMillis(), backup.id());
	}

	/**
	 * Writes the backup to disk before returning.
	 *
	 * @throws IOException if it could not be written
	 */
	public void saveNow(DeathBackup backup) throws IOException {
		store.writeNow(key(backup), backup.toJson());
	}

	/** Like {@link #saveNow}, queued: for history that guards nothing. */
	public void saveLater(DeathBackup backup) {
		store.write(key(backup), backup.toJson());
	}

	/** The backup of a corpse, if one was kept. */
	public Optional<DeathBackup> of(CorpseRecord corpse) {
		return read(key(corpse.owner(), corpse.createdMillis(), corpse.id()));
	}

	/** The owner's backup with this corpse ID. */
	public Optional<DeathBackup> find(UUID owner, String id) {
		String suffix = "-" + id;
		for (String key : store.keys(folder(owner))) {
			if (key.endsWith(suffix)) {
				return read(key);
			}
		}
		return Optional.empty();
	}

	/** The owner's backups, newest first. Damaged files are skipped (and kept on disk). */
	public List<DeathBackup> ofOwner(UUID owner) {
		List<String> keys = new ArrayList<>(store.keys(folder(owner)));
		List<DeathBackup> out = new ArrayList<>();
		for (int i = keys.size() - 1; i >= 0; i--) {
			read(keys.get(i)).ifPresent(out::add);
		}
		return out;
	}

	private Optional<DeathBackup> read(String key) {
		return store.read(key).flatMap(DeathBackup::fromJson).filter(b -> key(b).equals(key));
	}

	/**
	 * Deletes the owner's oldest backups beyond {@code keep}, and those older than {@code maxAgeMillis}
	 * (0: no age limit). Returns how many were deleted.
	 */
	public int prune(UUID owner, int keep, long maxAgeMillis, long now) {
		List<String> keys = store.keys(folder(owner));
		int deleted = 0;
		for (int i = 0; i < keys.size(); i++) {
			boolean tooMany = i < keys.size() - Math.max(0, keep);
			if (tooMany || (maxAgeMillis > 0 && now - createdOf(keys.get(i)) > maxAgeMillis)) {
				store.delete(keys.get(i));
				deleted++;
			}
		}
		return deleted;
	}

	/** The time in a key's last segment, or {@code Long.MAX_VALUE} (never too old) if it has none. */
	static long createdOf(String key) {
		String name = key.substring(key.lastIndexOf('/') + 1);
		int dash = name.indexOf('-');
		try {
			return Long.parseLong(dash < 0 ? name : name.substring(0, dash));
		} catch (NumberFormatException e) {
			return Long.MAX_VALUE;
		}
	}
}
