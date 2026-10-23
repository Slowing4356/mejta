package com.mejta.mejtaeconomy.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mejta.mejtacore.api.economy.EconomyResult.Status;

class LedgerTest {
	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

	@Test
	void newAccountsGetTheStartingBalance() {
		Ledger l = new Ledger(2);
		l.setStartingBalance(l.toMinor(25.5));
		assertTrue(l.ensure(A, Optional.of("Alex")));
		assertFalse(l.ensure(A, Optional.of("Alex2")));
		assertEquals(2550, l.balance(A));
		assertEquals("Alex2", l.get(A).orElseThrow().name());
	}

	@Test
	void withdrawNeverGoesNegative() {
		Ledger l = new Ledger(2);
		assertEquals(Status.NO_ACCOUNT, l.withdraw(A, 1).status());
		l.deposit(A, 500);
		assertEquals(Status.INSUFFICIENT_FUNDS, l.withdraw(A, 501).status());
		assertEquals(500, l.balance(A));
		assertEquals(0, l.withdraw(A, 500).balance());
		assertEquals(Status.INVALID_AMOUNT, l.deposit(A, 0).status());
		assertEquals(Status.INVALID_AMOUNT, l.deposit(A, -5).status());
	}

	@Test
	void transferIsAllOrNothingAndKeepsTheTotal() {
		Ledger l = new Ledger(2);
		l.setMaxBalance(1000);
		l.deposit(A, 800);
		l.deposit(B, 900);
		assertEquals(Status.BALANCE_LIMIT, l.transfer(A, B, 200).status());
		assertEquals(Status.INSUFFICIENT_FUNDS, l.transfer(A, B, 801).status());
		assertEquals(Status.INVALID_AMOUNT, l.transfer(A, A, 10).status());
		assertEquals(1700, l.total());
		assertEquals(700, l.transfer(A, B, 100).balance());
		assertEquals(1000, l.balance(B));
		assertEquals(1700, l.total());
	}

	@Test
	void depositRespectsTheLimitWithoutOverflow() {
		Ledger l = new Ledger(2);
		l.deposit(A, Ledger.HARD_MAX);
		assertEquals(Status.BALANCE_LIMIT, l.deposit(A, Long.MAX_VALUE).status());
		assertEquals(Ledger.HARD_MAX, l.balance(A));
	}

	@Test
	void rankingAndTop() {
		Ledger l = new Ledger(0);
		l.ensure(A, Optional.of("alex"));
		l.ensure(B, Optional.of("bea"));
		l.deposit(B, 10);
		assertEquals(1, l.rank(B));
		assertEquals(2, l.rank(A));
		assertEquals("bea", l.top(1).get(0).name());
		l.deposit(A, 20);
		assertEquals(1, l.rank(A));
	}

	@Test
	void awayMoneyIsShownOnce() {
		Ledger l = new Ledger(2);
		l.deposit(A, 100);
		l.addAway(A, 100);
		l.addAway(A, 50);
		assertEquals(150, l.takeAway(A));
		assertEquals(0, l.takeAway(A));
	}

	@Test
	void savesAndConvertsDecimals() {
		Ledger l = new Ledger(2);
		l.ensure(A, Optional.of("alex"));
		l.deposit(A, 12_345);
		l.addAway(A, 99);
		Ledger same = new Ledger(2);
		assertEquals(0, same.load(l.toJson()));
		assertEquals(12_345, same.balance(A));
		assertEquals("alex", same.get(A).orElseThrow().name());
		assertEquals(99, same.takeAway(A));

		Ledger whole = new Ledger(0);
		whole.load(l.toJson());
		assertEquals(123, whole.balance(A));
		Ledger finer = new Ledger(3);
		finer.load(l.toJson());
		assertEquals(123_450, finer.balance(A));
	}

	@Test
	void skipsBrokenEntries() {
		Ledger l = new Ledger(2);
		l.deposit(A, 5);
		com.google.gson.JsonObject json = l.toJson();
		com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
		bad.addProperty("id", "not-a-uuid");
		bad.addProperty("balance", 5);
		json.getAsJsonArray("accounts").add(bad);
		Ledger loaded = new Ledger(2);
		assertEquals(1, loaded.load(json));
		assertEquals(5, loaded.balance(A));
	}

	@Test
	void settingsToMinorUnits() {
		Ledger l = new Ledger(2);
		assertEquals(1, l.toMinor(0.01));
		assertEquals(1250, l.toMinor(12.505));
		assertEquals(0, l.toMinor(-3));
		assertEquals(0, l.toMinor(Double.NaN));
	}
}
