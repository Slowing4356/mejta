package com.mejta.mejtaeconomy.data;

import java.math.BigDecimal;
import java.util.Locale;

import com.mejta.mejtaeconomy.EconomySettings.SymbolPosition;

/**
 * How amounts are written: {@code $1,234.50}, {@code 1,234.50$} or {@code 1,234.50 coins}. Immutable; a
 * new one is made when the currency settings change.
 */
public record MoneyFormat(int digits, String symbol, SymbolPosition position, String singular, String plural, boolean showName) {
	public String format(long amount) {
		String number = String.format(Locale.ROOT, "%,." + digits + "f", BigDecimal.valueOf(amount).movePointLeft(digits));
		String text = switch (position) {
			case BEFORE -> number.startsWith("-") ? "-" + symbol + number.substring(1) : symbol + number;
			case AFTER -> number + symbol;
			case NONE -> number;
		};
		return showName ? text + " " + name(amount) : text;
	}

	/** {@code singular} for exactly one unit, else {@code plural}. */
	public String name(long amount) {
		return amount == BigDecimal.ONE.movePointRight(digits).longValue() ? singular : plural;
	}
}
