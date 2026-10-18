package com.mejta.mejtacore.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

class JsonDataStoreTest {
	@TempDir
	Path dir;
	ExecutorService io;
	JsonDataStore store;

	@BeforeEach
	void setUp() {
		io = Executors.newSingleThreadExecutor();
		store = new JsonDataStore("test", dir, io, 3);
	}

	@AfterEach
	void tearDown() throws Exception {
		io.shutdown();
		io.awaitTermination(5, TimeUnit.SECONDS);
	}

	static JsonObject doc(int n) {
		JsonObject o = new JsonObject();
		o.addProperty("n", n);
		return o;
	}

	static int n(JsonObject o) {
		return o.get("n").getAsInt();
	}

	@Test
	void readSeesQueuedWritesAndDeletes() throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		io.execute(() -> {
			try {
				blocked.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		store.write("a/b", doc(1));
		assertEquals(1, n(store.read("a/b").orElseThrow()), "visible before it is on disk");
		assertFalse(Files.exists(dir.resolve("a/b.json")));
		store.delete("a/b");
		assertTrue(store.read("a/b").isEmpty());
		store.write("a/b", doc(2));
		blocked.countDown();
		store.flush().get(5, TimeUnit.SECONDS);
		assertEquals(2, n(new JsonDataStore("test", dir, io, 3).read("a/b").orElseThrow()), "a fresh store reads it from disk");
	}

	@Test
	void valuesAreCopied() throws Exception {
		JsonObject value = doc(1);
		store.write("k", value);
		value.addProperty("n", 99);
		store.read("k").orElseThrow().addProperty("n", 42);
		assertEquals(1, n(store.read("k").orElseThrow()));
	}

	@Test
	void backupsRotateAndRecoverCorruptFile() throws Exception {
		for (int i = 1; i <= 5; i++) {
			store.writeDurable("k", doc(i)).get(5, TimeUnit.SECONDS);
		}
		assertTrue(Files.exists(dir.resolve("k.json.bak1")));
		assertTrue(Files.exists(dir.resolve("k.json.bak3")));
		assertFalse(Files.exists(dir.resolve("k.json.bak4")));
		Files.writeString(dir.resolve("k.json"), "{ broken");
		assertEquals(4, n(store.read("k").orElseThrow()), "newest backup used");
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("k.json.broken-")), "corrupt file kept aside");
		}
	}

	@Test
	void deleteRemovesBackupsSoNothingResurrects() throws Exception {
		store.writeDurable("k", doc(1)).get(5, TimeUnit.SECONDS);
		store.writeDurable("k", doc(2)).get(5, TimeUnit.SECONDS);
		store.delete("k");
		store.flush().get(5, TimeUnit.SECONDS);
		assertTrue(new JsonDataStore("test", dir, io, 3).read("k").isEmpty());
		assertFalse(Files.exists(dir.resolve("k.json.bak1")));
	}

	@Test
	void writeNowIsOnDiskWhenItReturns() throws Exception {
		store.writeNow("staffmode/x", doc(7));
		assertEquals(7, n(new JsonDataStore("test", dir, io, 3).read("staffmode/x").orElseThrow()));
	}

	@Test
	void writeNowSupersedesQueuedWrite() throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		io.execute(() -> {
			try {
				blocked.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		var queued = store.writeDurable("k", doc(1));
		store.writeNow("k", doc(2));
		assertTrue(queued.isDone(), "the durable promise is kept by the newer durable write");
		blocked.countDown();
		store.flush().get(5, TimeUnit.SECONDS);
		assertEquals(2, n(new JsonDataStore("test", dir, io, 3).read("k").orElseThrow()), "the older value never overwrites it");
	}

	@Test
	void failedWriteNowLeavesPreviousState() throws Exception {
		store.writeNow("k", doc(1));
		// A non-empty folder where the temp file must go: the write fails.
		Files.createDirectories(dir.resolve("k.json.tmp"));
		Files.writeString(dir.resolve("k.json.tmp/blocker"), "x");
		assertThrows(IOException.class, () -> store.writeNow("k", doc(2)));
		assertEquals(1, n(store.read("k").orElseThrow()));
	}

	@Test
	void keysListFolderIncludingPending() throws Exception {
		store.writeNow("frozen/" + UUID.randomUUID(), doc(1));
		store.write("frozen/b", doc(2));
		store.write("frozen/deep/c", doc(3));
		store.write("other", doc(4));
		List<String> keys = store.keys("frozen");
		assertEquals(2, keys.size(), keys.toString());
		assertTrue(keys.contains("frozen/b"));
		store.delete("frozen/b");
		assertFalse(store.keys("frozen").contains("frozen/b"));
		assertTrue(store.keys("").contains("other"));
	}

	@Test
	void keysCannotEscapeTheFolder() {
		for (String bad : List.of("../x", "a/../../x", "/abs", "a//b", "A", "a\\b", "c:/x", ".hidden", "a/.b", "", "con", "a/nul.txt",
				"x".repeat(129))) {
			assertThrows(IllegalArgumentException.class, () -> store.write(bad, doc(1)), bad);
			assertThrows(IllegalArgumentException.class, () -> store.read(bad), bad);
		}
		assertTrue(DataStore.isValidKey(DataStore.playerKey("vanish", UUID.fromString("0B4C1E62-0000-4000-8000-000000000001"))));
		assertEquals("vanish/0b4c1e62-0000-4000-8000-000000000001",
				DataStore.playerKey("vanish", UUID.fromString("0B4C1E62-0000-4000-8000-000000000001")));
	}
}
