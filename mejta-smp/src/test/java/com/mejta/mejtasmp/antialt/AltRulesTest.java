package com.mejta.mejtasmp.antialt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AltRulesTest {
	private static final UUID ME = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
	private static final UUID THIRD = UUID.fromString("00000000-0000-4000-8000-000000000003");

	@Test
	void onlineLimit() {
		assertEquals(AltRules.Decision.ALLOW, AltRules.decide(ME, 1, 2, Set.of(), 0));
		assertEquals(AltRules.Decision.TOO_MANY_ONLINE, AltRules.decide(ME, 2, 2, Set.of(), 0));
	}

	@Test
	void accountLimitLetsKnownAccountsBack() {
		assertEquals(AltRules.Decision.ALLOW, AltRules.decide(ME, 0, 2, Set.of(ME, OTHER), 2), "a known account may come back");
		assertEquals(AltRules.Decision.TOO_MANY_ACCOUNTS, AltRules.decide(THIRD, 0, 2, Set.of(ME, OTHER), 2));
		assertEquals(AltRules.Decision.ALLOW, AltRules.decide(THIRD, 0, 2, Set.of(ME, OTHER), 0), "0 = no limit");
	}
}
