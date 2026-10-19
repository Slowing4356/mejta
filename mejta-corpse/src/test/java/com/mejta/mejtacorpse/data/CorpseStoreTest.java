package com.mejta.mejtacorpse.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mejta.mejtacore.api.storage.DataStore;

class CorpseStoreTest {
	/** An in-memory store whose immediate writes can be made to fail, like a full disk. */
	static final class FlakyStore implements DataStore {
		final Map<String, JsonObject> docs = new TreeMap<>();
		boolean fail;

		@Override
		public Optional<JsonObject> read(String key) {
			return Optional.ofNullable(docs.get(key)).map(JsonObject::deepCopy);
		}

		@Override
		public void write(String key, JsonObject value) {
			docs.put(key, value.deepCopy());
		}

		@Override
		public CompletableFuture<Void> writeDurable(String key, JsonObject value) {
			write(key, value);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void writeNow(String key, JsonObject value) throws IOException {
			if (fail) {
				throw new IOException("disk full");
			}
			write(key, value);
		}

		@Override
		public void delete(String key) {
			docs.remove(key);
		}

		@Override
		public List<String> keys(String folder) {
			return docs.keySet().stream().filter(k -> k.startsWith(folder + "/") && k.indexOf('/', folder.length() + 1) < 0).toList();
		}

		@Override
		public CompletableFuture<Void> flush() {
			return CompletableFuture.completedFuture(null);
		}
	}

	static CorpseRecord corpse(String id, int diamonds) {
		return CorpseRecord.pending(id, UUID.fromString("00000000-0000-4000-8000-0000000000aa"), "Steve", 5, "minecraft:overworld", 0, 64, 0, 0,
				false, Map.of(0, JsonParser.parseString("{\"id\":\"minecraft:diamond\",\"count\":" + diamonds + "}")), null);
	}

	@Test
	void failedWriteChangesNothing() throws IOException {
		FlakyStore raw = new FlakyStore();
		CorpseStore store = new CorpseStore(raw);
		store.saveNow(corpse("aaaa0000", 5));
		raw.fail = true;
		assertThrows(IOException.class, () -> store.saveNow(corpse("aaaa0000", 1)));
		assertEquals(5, store.get("aaaa0000").orElseThrow().items().get(0).getAsJsonObject().get("count").getAsInt(), "memory unchanged");
		assertEquals(5, raw.read(CorpseStore.key("aaaa0000")).orElseThrow().getAsJsonObject("items").getAsJsonObject("0").get("count").getAsInt(),
				"disk unchanged");
	}

	@Test
	void reloadsWhatWasSavedAndSkipsDamage() throws IOException {
		FlakyStore raw = new FlakyStore();
		CorpseStore store = new CorpseStore(raw);
		store.saveNow(corpse("aaaa0001", 2));
		store.saveLater(corpse("aaaa0002", 3).withState(CorpseRecord.State.ACTIVE));
		raw.write("corpses/broken01", new JsonObject());
		CorpseStore fresh = new CorpseStore(raw);
		List<String> damaged = fresh.load();
		assertEquals(List.of("corpses/broken01"), damaged);
		assertEquals(2, fresh.size());
		assertEquals(CorpseRecord.State.ACTIVE, fresh.get("aaaa0002").orElseThrow().state());
		fresh.delete("aaaa0001");
		assertTrue(raw.read(CorpseStore.key("aaaa0001")).isEmpty());
	}

	@Test
	void idsAreShortAndUnique() throws IOException {
		CorpseStore store = new CorpseStore(new FlakyStore());
		String a = store.newId();
		assertTrue(a.matches("[0-9a-f]{8}"), a);
		store.saveNow(corpse(a, 1));
		for (int i = 0; i < 1000; i++) {
			assertNotEquals(a, store.newId());
		}
	}
}
