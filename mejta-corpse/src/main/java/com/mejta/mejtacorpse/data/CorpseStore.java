package com.mejta.mejtacorpse.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * Every corpse record, kept in memory and in the data store ({@code corpses/<id>}).
 *
 * <p>Every change that moves items is written with {@link DataStore#writeNow} (on disk before this
 * returns), and the in-memory copy changes only once that succeeded. A record that failed to save
 * therefore never differs from the one on disk.
 */
public final class CorpseStore {
	static final String FOLDER = "corpses";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final DataStore store;
	private final Map<String, CorpseRecord> records = new ConcurrentHashMap<>();
	private final RandomGenerator random = RandomGenerator.getDefault();

	public CorpseStore(DataStore store) {
		this.store = store;
	}

	/** Reads every record (server start). Damaged files are skipped and reported in the result. */
	public List<String> load() {
		records.clear();
		List<String> damaged = new ArrayList<>();
		for (String key : store.keys(FOLDER)) {
			Optional<CorpseRecord> r = store.read(key).flatMap(CorpseRecord::fromJson);
			if (r.isPresent() && key.equals(key(r.get().id()))) {
				records.put(r.get().id(), r.get());
			} else {
				damaged.add(key);
			}
		}
		return damaged;
	}

	public static String key(String id) {
		return FOLDER + "/" + id;
	}

	/** A new, unused ID of 8 hex digits (short enough to type in a command). */
	public String newId() {
		while (true) {
			StringBuilder b = new StringBuilder(8);
			for (int i = 0; i < 8; i++) {
				b.append(HEX[random.nextInt(16)]);
			}
			String id = b.toString();
			if (!records.containsKey(id) && store.read(key(id)).isEmpty()) {
				return id;
			}
		}
	}

	public Optional<CorpseRecord> get(String id) {
		return Optional.ofNullable(records.get(id));
	}

	public Collection<CorpseRecord> all() {
		return List.copyOf(records.values());
	}

	public List<CorpseRecord> ofOwner(UUID owner) {
		return records.values().stream().filter(r -> r.owner().equals(owner))
				.sorted((a, b) -> Long.compare(b.createdMillis(), a.createdMillis())).toList();
	}

	/**
	 * Writes the record to disk, then makes it current.
	 *
	 * @throws IOException if it could not be written; the current record is unchanged then
	 */
	public void saveNow(CorpseRecord record) throws IOException {
		JsonObject json = record.toJson();
		store.writeNow(key(record.id()), json);
		records.put(record.id(), record);
	}

	/** Like {@link #saveNow}, queued: for changes that move no items (which entities show the corpse). */
	public void saveLater(CorpseRecord record) {
		records.put(record.id(), record);
		store.write(key(record.id()), record.toJson());
	}

	/**
	 * Removes the record. Only for corpses that hold no items any more, or whose items were already
	 * written elsewhere: a removal that is lost in a crash then brings back at most an empty corpse.
	 */
	public void delete(String id) {
		records.remove(id);
		store.delete(key(id));
	}

	public int size() {
		return records.size();
	}

	public void clear() {
		records.clear();
	}
}
