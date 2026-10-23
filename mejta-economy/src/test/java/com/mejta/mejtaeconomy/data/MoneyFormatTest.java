package com.mejta.mejtaeconomy.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.mejta.mejtaeconomy.EconomySettings.SymbolPosition;

class MoneyFormatTest {
	@Test
	void formats() {
		assertEquals("$1,234.50", new MoneyFormat(2, "$", SymbolPosition.BEFORE, "coin", "coins", false).format(123_450));
		assertEquals("-$0.05", new MoneyFormat(2, "$", SymbolPosition.BEFORE, "coin", "coins", false).format(-5));
		assertEquals("12€", new MoneyFormat(0, "€", SymbolPosition.AFTER, "euro", "euros", false).format(12));
		assertEquals("1 coin", new MoneyFormat(0, "", SymbolPosition.NONE, "coin", "coins", true).format(1));
		assertEquals("1.00 coin", new MoneyFormat(2, "", SymbolPosition.NONE, "coin", "coins", true).format(100));
		assertEquals("2.00 coins", new MoneyFormat(2, "", SymbolPosition.NONE, "coin", "coins", true).format(200));
	}

	@Test
	void worldKeys() {
		String key = WorldKeys.scoped("accounts", "My World!", "/saves/My World!");
		assertEquals(true, key.matches("worlds/[0-9a-f]{8}-my_world_/accounts"));
	}
}
