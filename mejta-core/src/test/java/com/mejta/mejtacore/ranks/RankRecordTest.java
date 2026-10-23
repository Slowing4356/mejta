package com.mejta.mejtacore.ranks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.rank.Rank;

class RankRecordTest {
	@Test
	void ranksAreOrdered() {
		assertTrue(Rank.GOLD.atLeast(Rank.SILVER));
		assertTrue(Rank.BRONZE.atLeast(Rank.BRONZE));
		assertFalse(Rank.MEMBER.atLeast(Rank.BRONZE));
		assertEquals(Optional.of(Rank.SILVER), Rank.byId(" Silver "));
		assertEquals(Optional.empty(), Rank.byId("vip"));
		assertEquals("bronze", Rank.BRONZE.id());
	}

	@Test
	void recordSurvivesJson() {
		RankRecord record = new RankRecord(Rank.GOLD, "Steve", RankRecord.STAFF, 1_700_000_000_000L);
		assertEquals(Optional.of(record), RankRecord.fromJson(record.toJson()));
	}

	@Test
	void unknownOrBrokenDocuments() {
		JsonObject json = new JsonObject();
		json.addProperty("rank", "emperor");
		assertEquals(Optional.empty(), RankRecord.fromJson(json));
		JsonObject sparse = new JsonObject();
		sparse.addProperty("rank", "bronze");
		sparse.addProperty("since", "yesterday");
		assertEquals(Optional.of(new RankRecord(Rank.BRONZE, "", RankRecord.STAFF, 0)), RankRecord.fromJson(sparse));
	}

	@Test
	void playtimeOnlyForPlayersWithNothingStored() {
		assertTrue(RankRecord.mayEarn(Optional.empty()));
		assertFalse(RankRecord.mayEarn(Optional.of(new RankRecord(Rank.MEMBER, "Alex", RankRecord.STAFF, 0))), "staff set Member");
		assertEquals(50 * 60 * 60 * 20L, RankRecord.earnTicks(50));
		assertEquals(Long.MAX_VALUE, RankRecord.earnTicks(0));
	}
}
