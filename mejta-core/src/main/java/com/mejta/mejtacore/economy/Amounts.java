package com.mejta.mejtacore.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalLong;

/** Parses and prints amounts in minor units; no game classes, so unit-testable. */
public final class Amounts {
	private Amounts() {
	}

	/**
	 * {@code "12.5"} with 2 digits is {@code 1250}. Accepts {@code ,} and {@code _} as thousands separators
	 * and a {@code k} or {@code m} suffix. Empty for anything that is not a positive amount within range or
	 * that has more decimals than {@code fractionDigits}.
	 */
	public static OptionalLong parse(String text, int fractionDigits) {
		if (text == null) {
			return OptionalLong.empty();
		}
		String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
		if (s.isEmpty() || s.length() > 40) {
			return OptionalLong.empty();
		}
		int shift = 0;
		char last = s.charAt(s.length() - 1);
		if (last == 'k' || last == 'm') {
			shift = last == 'k' ? 3 : 6;
			s = s.substring(0, s.length() - 1);
		}
		if (!s.matches("[0-9]*(\\.[0-9]*)?") || s.equals(".") || s.isEmpty()) {
			return OptionalLong.empty();
		}
		try {
			BigDecimal minor = new BigDecimal(s).movePointRight(shift + fractionDigits);
			if (minor.signum() <= 0 || minor.stripTrailingZeros().scale() > 0) {
				return OptionalLong.empty();
			}
			return OptionalLong.of(minor.longValueExact());
		} catch (ArithmeticException | NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	/** {@code 123456} with 2 digits is {@code "1,234.56"}; whole amounts keep their decimals. */
	public static String plain(long amount, int fractionDigits) {
		BigDecimal value = BigDecimal.valueOf(amount).movePointLeft(fractionDigits).setScale(fractionDigits, RoundingMode.UNNECESSARY);
		String pattern = "%,." + fractionDigits + "f";
		return String.format(Locale.ROOT, pattern, value);
	}
}
