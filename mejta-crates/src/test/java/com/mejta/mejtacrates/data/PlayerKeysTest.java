package com.mejta.mejtacrates.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class PlayerKeysTest {
	@Test
	void keysNeverGoNegativeOrOverflow() {
		PlayerKeys keys = PlayerKeys.empty("Steve").addKeys("vote", 3).addKeys("vote", -5);
		assertEquals(0, keys.keys("vote"));
		assertFalse(keys.keys().containsKey("vote"), "zero counts are not stored");
		assertEquals(PlayerKeys.MAX_KEYS, keys.addKeys("vote", Integer.MAX_VALUE).addKeys("vote", Integer.MAX_VALUE).keys("vote"));
	}

	@Test
	void roundTripWithOpenings() {
		PlayerKeys.Opening opening = new PlayerKeys.Opening("abc123def456", "vote", "Vote Crate", Reward.ofMoney(2, 500), 1234, "physical:0123456789abcdef");
		PlayerKeys keys = PlayerKeys.empty("Alex").addKeys("vote", 2).addPhysical("rare", 1).withOpening(opening);
		List<String> problems = new ArrayList<>();
		PlayerKeys read = PlayerKeys.fromJson(JsonParser.parseString(keys.toJson().toString()).getAsJsonObject(), problems);
		assertEquals(keys, read);
		assertTrue(problems.isEmpty());
		assertTrue(read.opening("abc123def456").isPresent());
		assertTrue(read.withoutOpening("abc123def456").pending().isEmpty());
	}

	@Test
	void mergedKeysAreRememberedByToken() {
		PlayerKeys byName = PlayerKeys.empty("Newbie").addKeys("vote", 5).addPhysical("rare", 2);
		PlayerKeys merged = PlayerKeys.empty("Newbie").addKeys("vote", 1).merge(byName, "t1");
		assertEquals(6, merged.keys("vote"));
		assertEquals(2, merged.physical().get("rare").intValue());
		assertTrue(merged.hasClaimed("t1"));
		assertFalse(merged.hasClaimed("t2"));
		PlayerKeys many = merged;
		for (int i = 0; i < PlayerKeys.MAX_CLAIMED + 5; i++) {
			many = many.merge(PlayerKeys.empty("Newbie"), "x" + i);
		}
		assertEquals(PlayerKeys.MAX_CLAIMED, many.claimed().size(), "only the newest tokens are kept");
		assertTrue(many.hasClaimed("x" + (PlayerKeys.MAX_CLAIMED + 4)));
	}
}
