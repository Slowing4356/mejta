package com.mejta.mejtacorpse.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class CorpseRecordTest {
	static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

	static CorpseRecord sample() {
		return CorpseRecord.pending("0a1b2c3d", OWNER, "Steve", 1_000, "minecraft:overworld", 1.5, 64, -2.5, 90, false,
				Map.of(0, JsonParser.parseString("{\"id\":\"minecraft:diamond\",\"count\":3}"),
						39, JsonParser.parseString("{\"id\":\"minecraft:iron_helmet\"}")),
				new CorpseRecord.Skin("dGV4dHVyZXM=", "c2lnbmF0dXJl"));
	}

	@Test
	void roundTripsThroughJson() {
		CorpseRecord r = sample();
		assertEquals(r, CorpseRecord.fromJson(r.toJson()).orElseThrow());
		CorpseRecord active = r.withState(CorpseRecord.State.ACTIVE).withEntities(UUID.randomUUID(), UUID.randomUUID());
		assertEquals(active, CorpseRecord.fromJson(active.toJson()).orElseThrow());
		CorpseRecord noSkin = new CorpseRecord("x", OWNER, "A", 0, CorpseRecord.State.ACTIVE, "minecraft:the_end", 0, 0, 0, 0, true,
				Map.of(), null, null, null);
		assertEquals(noSkin, CorpseRecord.fromJson(noSkin.toJson()).orElseThrow());
	}

	@Test
	void copiesNeverShareItems() {
		CorpseRecord r = sample();
		JsonObject json = r.toJson();
		json.getAsJsonObject("items").getAsJsonObject("0").addProperty("count", 64);
		assertEquals(3, r.items().get(0).getAsJsonObject().get("count").getAsInt(), "the record is not changed through its JSON");
		CorpseRecord emptied = r.withItems(Map.of());
		assertTrue(emptied.isEmpty());
		assertFalse(r.isEmpty());
	}

	@Test
	void ownsOnlyItsEntities() {
		UUID body = UUID.randomUUID();
		UUID hitbox = UUID.randomUUID();
		CorpseRecord r = sample().withEntities(body, hitbox);
		assertTrue(r.owns(body));
		assertTrue(r.owns(hitbox));
		assertFalse(r.owns(UUID.randomUUID()));
		assertFalse(sample().owns(body), "no entities yet");
	}

	@Test
	void damagedRecordsAreRejectedAndBadSlotsSkipped() {
		assertTrue(CorpseRecord.fromJson(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
		JsonObject json = sample().toJson();
		json.addProperty("state", "EXPLODED");
		assertTrue(CorpseRecord.fromJson(json).isEmpty());
		JsonObject slots = sample().toJson();
		slots.getAsJsonObject("items").add("x", JsonParser.parseString("{}"));
		slots.getAsJsonObject("items").add("99", JsonParser.parseString("{}"));
		slots.getAsJsonObject("items").add("-1", JsonParser.parseString("{}"));
		assertEquals(2, CorpseRecord.fromJson(slots).orElseThrow().items().size());
		JsonObject noSkinField = sample().toJson();
		noSkinField.remove("skin");
		assertNull(CorpseRecord.fromJson(noSkinField).orElseThrow().skin());
	}
}
