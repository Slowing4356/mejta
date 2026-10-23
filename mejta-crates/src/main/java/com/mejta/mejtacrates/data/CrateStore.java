package com.mejta.mejtacrates.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * MejtaCrates' files in MejtaCore's data store ({@code mejta-data/mejtacrates/store/}):
 * <ul>
 * <li>{@code crates/<id>}: one crate each, editable by hand (then {@code /crates admin reload});</li>
 * <li>{@code players/<uuid>}: keys and unfinished openings, see {@link PlayerKeys};</li>
 * <li>{@code names/n-<name>}: keys given by name to someone who has never joined, moved to the player at their first join;</li>
 * <li>{@code serials/<serial>}: physical key batches, see {@link KeySerial};</li>
 * <li>{@code blocks}: the blocks that are crates.</li>
 * </ul>
 * Everything that guards against duplication (keys, openings, serials) is written with {@code writeNow}: on the
 * disk before the game changes.
 */
public final class CrateStore {
	/** A Minecraft account name. */
	public static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	/** A crate block: the crate opened by right-clicking the block at this position. */
	public record CrateBlock(String dimension, int x, int y, int z, String crate) {
		public String position() {
			return dimension + " " + x + " " + y + " " + z;
		}
	}

	private final DataStore store;
	private final Consumer<String> problems;
	private final Map<String, Crate> crates = new LinkedHashMap<>();
	private final Map<String, CrateBlock> blocks = new LinkedHashMap<>();

	/** @param problems receives a line for each file or entry that could not be read */
	public CrateStore(DataStore store, Consumer<String> problems) {
		this.store = store;
		this.problems = problems;
	}

	public DataStore raw() {
		return store;
	}

	// ---------------------------------------------------------------- crates

	/** Reads every crate and the crate blocks from disk again. */
	public synchronized void load() {
		crates.clear();
		for (String key : store.keys("crates")) {
			String id = key.substring(key.lastIndexOf('/') + 1);
			if (!Crate.isValidId(id)) {
				problems.accept("ignored crate file " + key + ": invalid crate ID");
				continue;
			}
			store.read(key).ifPresent(json -> {
				try {
					List<String> issues = new ArrayList<>();
					crates.put(id, Crate.fromJson(id, json, issues));
					issues.forEach(problems);
				} catch (RuntimeException e) {
					problems.accept("unreadable crate " + id + ": " + e.getMessage());
				}
			});
		}
		blocks.clear();
		store.read("blocks").ifPresent(json -> {
			if (json.has("blocks") && json.get("blocks").isJsonArray()) {
				for (JsonElement e : json.getAsJsonArray("blocks")) {
					try {
						JsonObject b = e.getAsJsonObject();
						CrateBlock block = new CrateBlock(b.get("dimension").getAsString(), b.get("x").getAsInt(), b.get("y").getAsInt(),
								b.get("z").getAsInt(), b.get("crate").getAsString());
						blocks.put(block.position(), block);
					} catch (RuntimeException ex) {
						problems.accept("unreadable crate block " + e);
					}
				}
			}
		});
	}

	public synchronized Optional<Crate> crate(String id) {
		return Optional.ofNullable(crates.get(id == null ? "" : id.toLowerCase(Locale.ROOT)));
	}

	public synchronized List<Crate> crates() {
		return List.copyOf(crates.values());
	}

	public synchronized boolean isEmpty() {
		return crates.isEmpty();
	}

	public synchronized void save(Crate crate) {
		crates.put(crate.id(), crate);
		store.write("crates/" + crate.id(), crate.toJson());
	}

	public synchronized void delete(String id) {
		crates.remove(id);
		store.delete("crates/" + id);
		if (blocks.values().removeIf(b -> b.crate().equals(id))) {
			saveBlocks();
		}
	}

	// ---------------------------------------------------------------- blocks

	public synchronized Optional<CrateBlock> block(String dimension, int x, int y, int z) {
		return Optional.ofNullable(blocks.get(dimension + " " + x + " " + y + " " + z));
	}

	public synchronized Collection<CrateBlock> blocks() {
		return List.copyOf(blocks.values());
	}

	public synchronized void putBlock(CrateBlock block) {
		blocks.put(block.position(), block);
		saveBlocks();
	}

	public synchronized boolean removeBlock(String dimension, int x, int y, int z) {
		boolean removed = blocks.remove(dimension + " " + x + " " + y + " " + z) != null;
		if (removed) {
			saveBlocks();
		}
		return removed;
	}

	private void saveBlocks() {
		JsonArray array = new JsonArray();
		for (CrateBlock b : blocks.values()) {
			JsonObject json = new JsonObject();
			json.addProperty("dimension", b.dimension());
			json.addProperty("x", b.x());
			json.addProperty("y", b.y());
			json.addProperty("z", b.z());
			json.addProperty("crate", b.crate());
			array.add(json);
		}
		JsonObject doc = new JsonObject();
		doc.add("blocks", array);
		store.write("blocks", doc);
	}

	// ---------------------------------------------------------------- players

	public PlayerKeys player(UUID player, String name) {
		return store.read(DataStore.playerKey("players", player)).map(json -> {
			List<String> issues = new ArrayList<>();
			PlayerKeys keys = PlayerKeys.fromJson(json, issues);
			issues.forEach(i -> problems.accept(name + ": " + i));
			return keys;
		}).orElseGet(() -> PlayerKeys.empty(name));
	}

	/** On disk before it returns. @throws IOException if it could not be written: nothing changed then */
	public void savePlayer(UUID player, PlayerKeys keys) throws IOException {
		store.writeNow(DataStore.playerKey("players", player), keys.toJson());
	}

	/** UUIDs of players with a crate file. */
	public List<UUID> players() {
		List<UUID> out = new ArrayList<>();
		for (String key : store.keys("players")) {
			try {
				out.add(UUID.fromString(key.substring(key.lastIndexOf('/') + 1)));
			} catch (IllegalArgumentException ignored) {
				// not a player file
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- keys for names that never joined

	public static String nameKey(String name) {
		if (!PLAYER_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("not a player name: " + name);
		}
		return "names/n-" + name.toLowerCase(Locale.ROOT);
	}

	/**
	 * Keys given to a name. {@code token} is new for each new file and is remembered by the player who gets them,
	 * so that a file left behind by a crash is never added twice.
	 */
	public record NamedKeys(String token, PlayerKeys keys) {
	}

	public Optional<NamedKeys> byName(String name) {
		return store.read(nameKey(name)).map(json -> new NamedKeys(json.has("token") ? json.get("token").getAsString() : "none",
				PlayerKeys.fromJson(json, new ArrayList<>())));
	}

	public void saveByName(String name, NamedKeys keys) throws IOException {
		JsonObject json = keys.keys().toJson();
		json.addProperty("token", keys.token());
		store.writeNow(nameKey(name), json);
	}

	public void deleteByName(String name) {
		store.delete(nameKey(name));
	}

	// ---------------------------------------------------------------- physical key serials

	public Optional<KeySerial> serial(String serial) {
		if (!KeySerial.isValidSerial(serial)) {
			return Optional.empty();
		}
		return store.read("serials/" + serial).flatMap(json -> {
			try {
				return Optional.of(KeySerial.fromJson(serial, json));
			} catch (RuntimeException e) {
				problems.accept("unreadable key serial " + serial + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}

	public void saveSerial(KeySerial serial) throws IOException {
		store.writeNow("serials/" + serial.serial(), serial.toJson());
	}
}
