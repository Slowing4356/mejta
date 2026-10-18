package com.mejta.mejtacore.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;

class AuditChainTest {
	static AuditEntry entry(String action, String detail) {
		return AuditEntry.builder("mejtautils", action)
				.actor(AuditActor.player("Mod", UUID.fromString("00000000-0000-4000-8000-000000000001")))
				.target(UUID.fromString("00000000-0000-4000-8000-000000000002"), "Steve")
				.outcome(AuditOutcome.SUCCESS)
				.detail("item", detail)
				.build();
	}

	/** Lines as they come back from disk: written by a default Gson (HTML escaping on), parsed again. */
	static List<JsonObject> roundTrip(List<JsonObject> lines) {
		List<JsonObject> out = new ArrayList<>();
		for (JsonObject line : lines) {
			out.add(JsonParser.parseString(new Gson().toJson(line)).getAsJsonObject());
		}
		return out;
	}

	@Test
	void chainVerifiesAfterRoundTrip() {
		List<JsonObject> lines = new ArrayList<>();
		AuditChain chain = new AuditChain(() -> null, lines::add);
		chain.append(entry("invsee.edit", "minecraft:diamond x3 <html> & \"quotes\" = ü"));
		chain.append(entry("freeze.start", "reason"));
		chain.append(entry("freeze.stop", ""));
		assertEquals(AuditChain.GENESIS, lines.get(0).get("prev").getAsString());
		AuditChain.Verification v = AuditChain.verify(roundTrip(lines), true);
		assertTrue(v.intact(), v.problem());
		assertEquals(3, v.verified());
	}

	@Test
	void continuesFromStoredHead() {
		List<JsonObject> lines = new ArrayList<>();
		new AuditChain(() -> null, lines::add).append(entry("a", "1"));
		String head = lines.get(0).get("hash").getAsString();
		new AuditChain(() -> head, lines::add).append(entry("b", "2")); // after a restart
		assertTrue(AuditChain.verify(roundTrip(lines), true).intact());
	}

	@Test
	void detectsEditedRemovedAndReorderedLines() {
		List<JsonObject> lines = new ArrayList<>();
		AuditChain chain = new AuditChain(() -> null, lines::add);
		for (int i = 0; i < 4; i++) {
			chain.append(entry("a", String.valueOf(i)));
		}
		List<JsonObject> edited = roundTrip(lines);
		edited.get(2).getAsJsonObject("details").addProperty("item", "changed");
		AuditChain.Verification v = AuditChain.verify(edited, true);
		assertFalse(v.intact());
		assertEquals(2, v.brokenAt());

		List<JsonObject> removed = roundTrip(lines);
		removed.remove(1);
		assertEquals(1, AuditChain.verify(removed, true).brokenAt());

		List<JsonObject> swapped = roundTrip(lines);
		JsonObject first = swapped.remove(2);
		swapped.add(3, first);
		assertFalse(AuditChain.verify(swapped, true).intact());

		List<JsonObject> rotated = roundTrip(lines).subList(2, 4);
		assertFalse(AuditChain.verify(rotated, true).intact(), "missing beginning is reported when expected");
		assertTrue(AuditChain.verify(rotated, false).intact(), "but accepted after rotation");
	}

	@Test
	void lineHoldsActorTargetOutcomeAndTime() {
		List<JsonObject> lines = new ArrayList<>();
		new AuditChain(() -> null, lines::add).append(entry("invsee.view", "x"));
		JsonObject line = lines.get(0);
		assertEquals("Mod", line.get("actor").getAsString());
		assertEquals("PLAYER", line.get("actor_kind").getAsString());
		assertEquals("Steve", line.get("target").getAsString());
		assertEquals("00000000-0000-4000-8000-000000000002", line.get("target_uuid").getAsString());
		assertEquals("SUCCESS", line.get("outcome").getAsString());
		assertFalse(line.get("ts").getAsString().isEmpty());
	}
}
