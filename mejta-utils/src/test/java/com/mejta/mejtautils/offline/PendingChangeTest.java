package com.mejta.mejtautils.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.mejta.mejtautils.offline.PendingChange.Container;
import com.mejta.mejtautils.offline.PendingChange.SlotRef;

class PendingChangeTest {
	static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
	static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

	static JsonElement item(String id, int count) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("count", count);
		return o;
	}

	static PendingChange change() {
		return new PendingChange(TARGET, "Steve", STAFF, "Mod", Instant.parse("2026-10-18T12:00:00Z"));
	}

	/** Live slots as encoded items; an item matches when its JSON is equal. */
	static PendingChange.Plan plan(PendingChange c, Map<SlotRef, JsonElement> live) {
		return c.plan(live::get, JsonElement::equals);
	}

	@Test
	void oneChangePerSlotAndUndo() {
		PendingChange c = change();
		assertTrue(c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5)));
		assertFalse(c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5)), "no second change for a slot");
		assertFalse(c.add(Container.INVENTORY, 3, item("minecraft:stone", 1)));
		assertTrue(c.add(Container.ENDER_CHEST, 3, item("minecraft:stone", 1)), "same index, other container");
		assertEquals(PendingChange.Kind.ADD, c.undo(Container.ENDER_CHEST, 3).orElseThrow().kind());
		assertTrue(c.addedItems().isEmpty());
	}

	@Test
	void appliesWhenLiveDataMatches() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		c.add(Container.ENDER_CHEST, 0, item("minecraft:stone", 1));
		Map<SlotRef, JsonElement> live = new HashMap<>();
		live.put(new SlotRef(Container.INVENTORY, 3), item("minecraft:diamond", 5));
		PendingChange.Plan p = plan(c, live);
		assertTrue(p.applicable());
		assertTrue(p.writes().containsKey(new SlotRef(Container.INVENTORY, 3)));
		assertEquals(null, p.writes().get(new SlotRef(Container.INVENTORY, 3)), "removed slot is emptied");
		assertEquals(item("minecraft:stone", 1), p.writes().get(new SlotRef(Container.ENDER_CHEST, 0)));
		assertEquals(List.of(item("minecraft:diamond", 5)), p.toClaims(), "removed item goes to the staff member's claims");
	}

	@Test
	void deletedRemovalsGoNowhere() {
		PendingChange c = change();
		c.setDeleteRemoved(true);
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		PendingChange.Plan p = plan(c, Map.of(new SlotRef(Container.INVENTORY, 3), item("minecraft:diamond", 5)));
		assertTrue(p.applicable());
		assertTrue(p.toClaims().isEmpty());
	}

	@Test
	void conflictChangesNothingAndReturnsAddedItems() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		c.add(Container.INVENTORY, 4, item("minecraft:stone", 1));
		// The save changed in between: only 4 diamonds now, and slot 4 is no longer empty.
		Map<SlotRef, JsonElement> live = Map.of(
				new SlotRef(Container.INVENTORY, 3), item("minecraft:diamond", 4),
				new SlotRef(Container.INVENTORY, 4), item("minecraft:dirt", 1));
		PendingChange.Plan p = plan(c, live);
		assertFalse(p.applicable());
		assertEquals(2, p.conflicts().size());
		assertTrue(p.writes().isEmpty(), "nothing is written to the player");
		assertEquals(List.of(item("minecraft:stone", 1)), p.toClaims(), "the staff member gets the added item back, not the diamonds");
	}

	@Test
	void removalOfAnAlreadyEmptySlotIsAConflict() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		assertFalse(plan(c, Map.of()).applicable());
	}

	@Test
	void lifeCycleAndClaims() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		c.markApplying();
		assertThrows(IllegalStateException.class, () -> c.add(Container.INVENTORY, 4, item("minecraft:stone", 1)),
				"no edits once applying started");
		c.markApplied(List.of(item("minecraft:diamond", 5)));
		assertFalse(c.isDone());
		assertEquals(item("minecraft:diamond", 5), c.takeClaim(0));
		assertTrue(c.isDone());
	}

	@Test
	void cancelReturnsOnlyAddedItems() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		c.add(Container.INVENTORY, 4, item("minecraft:stone", 1));
		c.markCancelled("cancelled by admin");
		assertEquals(PendingChange.State.CANCELLED, c.state());
		assertEquals(List.of(item("minecraft:stone", 1)), c.claims());
	}

	@Test
	void recoveryAfterACrashWhileApplying() {
		PendingChange c = change();
		c.remove(Container.INVENTORY, 3, item("minecraft:diamond", 5));
		c.add(Container.INVENTORY, 4, item("minecraft:stone", 1));
		c.markApplying();
		SlotRef s3 = new SlotRef(Container.INVENTORY, 3);
		SlotRef s4 = new SlotRef(Container.INVENTORY, 4);
		Map<SlotRef, JsonElement> before = Map.of(s3, item("minecraft:diamond", 5));
		Map<SlotRef, JsonElement> after = Map.of(s4, item("minecraft:stone", 1));
		Map<SlotRef, JsonElement> neither = Map.of(s3, item("minecraft:diamond", 5), s4, item("minecraft:stone", 1));
		assertEquals(PendingChange.Recovery.NOT_APPLIED, c.recover(before::get, JsonElement::equals));
		assertEquals(PendingChange.Recovery.ALREADY_APPLIED, c.recover(after::get, JsonElement::equals));
		assertEquals(PendingChange.Recovery.UNKNOWN, c.recover(neither::get, JsonElement::equals));
	}

	@Test
	void jsonRoundTrip() {
		PendingChange c = change();
		c.setDeleteRemoved(true);
		c.remove(Container.INVENTORY, 40, item("minecraft:shield", 1));
		c.add(Container.ENDER_CHEST, 26, item("minecraft:stone", 64));
		JsonObject json = JsonParser.parseString(c.toJson().toString()).getAsJsonObject();
		PendingChange back = PendingChange.fromJson(json);
		assertEquals(c.ops(), back.ops());
		assertEquals(PendingChange.State.OPEN, back.state());
		assertTrue(back.deleteRemoved());
		assertEquals(TARGET, back.target());
		assertEquals("Mod", back.staffName());
	}

	@Test
	void rejectsInvalidDocuments() {
		JsonObject json = change().toJson();
		json.getAsJsonArray("ops").add(op("REMOVE", "ENDER_CHEST", 27));
		assertThrows(IllegalArgumentException.class, () -> PendingChange.fromJson(json), "slot out of range");

		JsonObject dup = change().toJson();
		dup.getAsJsonArray("ops").add(op("REMOVE", "INVENTORY", 1));
		dup.getAsJsonArray("ops").add(op("ADD", "INVENTORY", 1));
		assertThrows(IllegalArgumentException.class, () -> PendingChange.fromJson(dup), "two changes for one slot");

		JsonObject badUuid = change().toJson();
		badUuid.addProperty("target", "../../etc");
		assertThrows(IllegalArgumentException.class, () -> PendingChange.fromJson(badUuid));
	}

	static JsonObject op(String kind, String container, int slot) {
		JsonObject j = new JsonObject();
		j.addProperty("kind", kind);
		j.addProperty("container", container);
		j.addProperty("slot", slot);
		j.add("item", new JsonPrimitive("x"));
		return j;
	}
}
