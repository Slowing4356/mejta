package com.mejta.mejtacorpse.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
	static final String RETIRED = "retired-entities";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final DataStore store;
	private final Map<String, CorpseRecord> records = new ConcurrentHashMap<>();
	/** Which corpse each corpse entity belongs to. */
	private final Map<UUID, String> byEntity = new ConcurrentHashMap<>();
	/**
	 * Entities of removed or replaced corpses that were in unloaded chunks: removed when they load again.
	 * They hold no items, so losing this list in a crash leaves at most a corpse that vanishes when clicked.
	 */
	private final Set<UUID> retired = ConcurrentHashMap.newKeySet();
	private final RandomGenerator random = RandomGenerator.getDefault();

	public CorpseStore(DataStore store) {
		this.store = store;
	}

	/** Reads every record (server start). Damaged files are skipped and reported in the result. */
	public List<String> load() {
		clear();
		List<String> damaged = new ArrayList<>();
		for (String key : store.keys(FOLDER)) {
			Optional<CorpseRecord> r = store.read(key).flatMap(CorpseRecord::fromJson);
			if (r.isPresent() && key.equals(key(r.get().id()))) {
				put(r.get());
			} else {
				damaged.add(key);
			}
		}
		store.read(RETIRED).ifPresent(doc -> {
			if (doc.has("uuids") && doc.get("uuids").isJsonArray()) {
				for (JsonElement e : doc.getAsJsonArray("uuids")) {
					try {
						retired.add(UUID.fromString(e.getAsString()));
					} catch (RuntimeException ignored) {
						// not a UUID: skipped
					}
				}
			}
		});
		return damaged;
	}

	private void put(CorpseRecord record) {
		CorpseRecord old = records.put(record.id(), record);
		if (old != null) {
			unindex(old);
		}
		for (UUID e : new UUID[] {record.body(), record.hitbox()}) {
			if (e != null) {
				byEntity.put(e, record.id());
			}
		}
	}

	private void unindex(CorpseRecord record) {
		for (UUID e : new UUID[] {record.body(), record.hitbox()}) {
			if (e != null) {
				byEntity.remove(e, record.id());
			}
		}
	}

	/** The corpse an entity belongs to, if it is one of the current entities of a corpse. */
	public Optional<CorpseRecord> ofEntity(UUID entity) {
		String id = byEntity.get(entity);
		return id == null ? Optional.empty() : get(id);
	}

	/** Marks an entity of a removed or replaced corpse, so it is removed once its chunk loads. */
	public void retire(UUID entity) {
		if (entity != null && retired.add(entity)) {
			saveRetired();
		}
	}

	/** Whether {@code entity} was retired; it is forgotten, as it is about to be removed. */
	public boolean takeRetired(UUID entity) {
		if (retired.remove(entity)) {
			saveRetired();
			return true;
		}
		return false;
	}

	private void saveRetired() {
		JsonObject doc = new JsonObject();
		JsonArray list = new JsonArray();
		retired.forEach(u -> list.add(u.toString()));
		doc.add("uuids", list);
		store.write(RETIRED, doc);
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
		put(record);
	}

	/** Like {@link #saveNow}, queued: for changes that move no items (which entities show the corpse). */
	public void saveLater(CorpseRecord record) {
		put(record);
		store.write(key(record.id()), record.toJson());
	}

	/**
	 * Removes the record. Only for corpses that hold no items any more, or whose items were already
	 * written elsewhere: a removal that is lost in a crash then brings back at most an empty corpse.
	 */
	public void delete(String id) {
		CorpseRecord old = records.remove(id);
		if (old != null) {
			unindex(old);
		}
		store.delete(key(id));
	}

	public int size() {
		return records.size();
	}

	public void clear() {
		records.clear();
		byEntity.clear();
		retired.clear();
	}
}
