package com.mejta.mejtacorpse.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BackupStoreTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

	private static DeathBackup backup(String id, long created) {
		return new DeathBackup(id, OWNER, "Steve", created, "minecraft:overworld", 0, 64, 0, CorpseStoreTest.corpse(id, 3).items(), List.of());
	}

	@Test
	void roundTripsThroughJsonWithHistory() {
		DeathBackup b = backup("0a1b2c3d", 1_000)
				.withEvent(new DeathBackup.Event(2_000, DeathBackup.Kind.TAKEN_BY_OTHER, "Alex", 12, ""))
				.withEvent(new DeathBackup.Event(3_000, DeathBackup.Kind.TAKEN_BY_OWNER, "Steve", 5, ""));
		assertEquals(b, DeathBackup.fromJson(b.toJson()).orElseThrow());
		assertEquals(5, b.takenByOwner());
		assertTrue(b.restored().isEmpty());
		assertTrue(b.withEvent(new DeathBackup.Event(4_000, DeathBackup.Kind.RESTORED, "Admin", 3, "")).restored().isPresent());
	}

	@Test
	void keepsOnlyTheNewestPerPlayer() throws IOException {
		CorpseStoreTest.FlakyStore raw = new CorpseStoreTest.FlakyStore();
		BackupStore store = new BackupStore(raw);
		for (int i = 1; i <= 12; i++) {
			store.saveNow(backup(String.format("%08x", i), i * 1_000L));
		}
		// Newer deaths sort last even when their time has more digits.
		store.saveNow(backup("0000abcd", 100_000L));
		assertEquals(3, store.prune(OWNER, 10, 0, 200_000L));
		List<DeathBackup> left = store.ofOwner(OWNER);
		assertEquals(10, left.size());
		assertEquals("0000abcd", left.get(0).id(), "newest first");
		assertEquals("00000004", left.get(9).id(), "the three oldest are gone");
		assertEquals("00000007", store.find(OWNER, "00000007").orElseThrow().id());
		assertTrue(store.find(OWNER, "00000001").isEmpty());
		assertTrue(store.ofOwner(UUID.randomUUID()).isEmpty(), "other players have their own folder");
	}

	@Test
	void dropsBackupsOlderThanTheAgeLimit() throws IOException {
		CorpseStoreTest.FlakyStore raw = new CorpseStoreTest.FlakyStore();
		BackupStore store = new BackupStore(raw);
		store.saveNow(backup("00000001", 1_000));
		store.saveNow(backup("00000002", 50_000));
		assertEquals(1, store.prune(OWNER, Integer.MAX_VALUE, 10_000, 55_000));
		assertEquals(List.of("00000002"), store.ofOwner(OWNER).stream().map(DeathBackup::id).toList());
	}

	@Test
	void aFailedWriteIsReported() {
		CorpseStoreTest.FlakyStore raw = new CorpseStoreTest.FlakyStore();
		raw.fail = true;
		assertThrows(IOException.class, () -> new BackupStore(raw).saveNow(backup("00000001", 1_000)));
		assertTrue(raw.docs.isEmpty());
	}
}
