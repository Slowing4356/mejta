package com.mejta.mejtacrates.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class CrateTest {
	private static JsonObject item(String id, int count) {
		JsonObject json = new JsonObject();
		json.addProperty("id", id);
		json.addProperty("count", count);
		return json;
	}

	@Test
	void roundTripKeepsEverything() {
		Crate crate = Crate.create("rare", "Rare Crate").withColor("aqua")
				.withReward(Reward.ofItem(10, item("minecraft:diamond", 3)))
				.withReward(Reward.ofCommand(2.5, "give {player} minecraft:elytra", Optional.of(item("minecraft:elytra", 1))).withName("Wings")
						.withBroadcast(Optional.of(true)))
				.withReward(Reward.ofMoney(1, 10_000));
		List<String> problems = new ArrayList<>();
		Crate read = Crate.fromJson("rare", JsonParser.parseString(crate.toJson().toString()).getAsJsonObject(), problems);
		assertEquals(crate, read);
		assertTrue(problems.isEmpty());
		assertEquals("AQUA", read.color());
	}

	@Test
	void badRewardsAreSkippedAndReported() {
		JsonObject json = JsonParser.parseString("""
				{"name": "Vote", "rewards": [
				  {"weight": 5, "items": [{"id": "minecraft:emerald", "count": 1}]},
				  {"weight": 0},
				  {"items": []},
				  "text",
				  {"weight": 1, "commands": ["/say hi", " "]}
				]}""").getAsJsonObject();
		List<String> problems = new ArrayList<>();
		Crate crate = Crate.fromJson("vote", json, problems);
		assertEquals(2, crate.rewards().size());
		assertEquals(3, problems.size());
		assertEquals(List.of("say hi"), crate.rewards().get(1).commands(), "a leading slash and blank commands are dropped");
	}

	@Test
	void idsAreChecked() {
		assertTrue(Crate.isValidId("vip_crate-2"));
		assertFalse(Crate.isValidId("VIP"));
		assertFalse(Crate.isValidId("../x"));
		assertFalse(Crate.isValidId("a".repeat(33)));
		assertThrows(IllegalArgumentException.class, () -> Crate.create("Bad Id", "x"));
		assertEquals("plain", Crate.create("plain", " ").name(), "a blank name falls back to the ID");
	}

	@Test
	void eligibleLeavesOutMoneyWithoutAnEconomy() {
		Crate crate = Crate.create("c", "C").withReward(Reward.ofMoney(1, 100)).withReward(Reward.ofItem(1, item("minecraft:stone", 1)));
		assertEquals(1, crate.eligible(r -> !r.paysMoney()).size());
		assertEquals(2, crate.eligible(r -> true).size());
	}
}
