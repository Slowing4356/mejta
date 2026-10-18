package com.mejta.mejtautils.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

class ScopedDataStoreTest {
	/** Keys as MejtaCore's store lists them: full keys, only documents directly inside the folder. */
	static final class MemoryStore implements DataStore {
		final Map<String, JsonObject> docs = new TreeMap<>();

		@Override
		public Optional<JsonObject> read(String key) {
			return Optional.ofNullable(docs.get(check(key))).map(JsonObject::deepCopy);
		}

		@Override
		public void write(String key, JsonObject value) {
			docs.put(check(key), value.deepCopy());
		}

		@Override
		public CompletableFuture<Void> writeDurable(String key, JsonObject value) {
			write(key, value);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void writeNow(String key, JsonObject value) {
			write(key, value);
		}

		@Override
		public void delete(String key) {
			docs.remove(check(key));
		}

		@Override
		public List<String> keys(String folder) {
			String prefix = folder.isEmpty() ? "" : folder + "/";
			List<String> out = new ArrayList<>();
			for (String key : docs.keySet()) {
				if (key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0) {
					out.add(key);
				}
			}
			return out;
		}

		@Override
		public CompletableFuture<Void> flush() {
			return CompletableFuture.completedFuture(null);
		}

		private static String check(String key) {
			if (!DataStore.isValidKey(key)) {
				throw new IllegalArgumentException("invalid key " + key);
			}
			return key;
		}
	}

	static JsonObject doc(String value) {
		JsonObject o = new JsonObject();
		o.addProperty("v", value);
		return o;
	}

	static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

	@Test
	void unscopedUsesTheKeysAsTheyAre() {
		MemoryStore raw = new MemoryStore();
		ScopedDataStore store = new ScopedDataStore(raw);
		store.write(DataStore.playerKey("frozen", PLAYER), doc("server"));
		assertTrue(raw.docs.containsKey("frozen/" + PLAYER));
		assertEquals(List.of("frozen/" + PLAYER), store.keys("frozen"));
	}

	@Test
	void worldsDoNotSeeEachOther() {
		MemoryStore raw = new MemoryStore();
		ScopedDataStore store = new ScopedDataStore(raw);
		String key = DataStore.playerKey("staffmode", PLAYER);

		store.scopeToWorld(ScopedDataStore.worldId("New World", "/saves/New World"));
		store.write(key, doc("first"));
		assertEquals(List.of(key), store.keys("staffmode"));

		store.scopeToWorld(ScopedDataStore.worldId("Other", "/saves/Other"));
		assertTrue(store.read(key).isEmpty());
		assertTrue(store.keys("staffmode").isEmpty());
		store.write(key, doc("second"));

		store.scopeToWorld(ScopedDataStore.worldId("New World", "/saves/New World"));
		assertEquals("first", store.read(key).orElseThrow().get("v").getAsString());
		store.delete(key);
		assertTrue(store.read(key).isEmpty());

		store.unscope();
		assertTrue(store.read(key).isEmpty(), "a dedicated server's keys are separate from every world");
		assertEquals(1, raw.docs.size());
	}

	@Test
	void worldIdsAreValidAndDistinct() {
		String a = ScopedDataStore.worldId("My World (2)!", "/home/a/saves/My World (2)!");
		String b = ScopedDataStore.worldId("My World (2)!", "/home/b/saves/My World (2)!");
		assertNotEquals(a, b, "same folder name, different folder");
		assertTrue(a.endsWith("-my_world__2__"), a);
		assertTrue(DataStore.isValidKey("worlds/" + a));
		String long1 = ScopedDataStore.worldId("x".repeat(300), "/p");
		assertTrue(long1.length() <= 8 + 1 + 24, long1);
		assertTrue(DataStore.isValidKey("worlds/" + ScopedDataStore.worldId("....", "/p")));
		assertTrue(DataStore.isValidKey("worlds/" + ScopedDataStore.worldId("", "/p")));
		// The longest key MejtaUtils writes still fits under the prefix.
		String longest = "worlds/" + long1 + "/staffmode-history/" + PLAYER + "-" + "1".repeat(13);
		assertTrue(DataStore.isValidKey(longest), longest);
	}

	@Test
	void topLevelListingStaysInsideTheWorld() {
		MemoryStore raw = new MemoryStore();
		ScopedDataStore store = new ScopedDataStore(raw);
		raw.write("outside", doc("x"));
		store.scopeToWorld("abcd1234-w");
		store.write("inside", doc("y"));
		assertEquals(List.of("inside"), store.keys(""));
		assertThrows(IllegalArgumentException.class, () -> store.scopeToWorld("../escape"));
		assertFalse(store.keys("").contains("outside"));
	}
}
