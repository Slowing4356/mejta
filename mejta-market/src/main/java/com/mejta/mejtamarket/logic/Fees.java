package com.mejta.mejtamarket.logic;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Listing fees and sales tax, in minor units. Both are percentages of a price, rounded half up, and both
 * leave the economy. Nobody ever gets less than nothing, and every result fits in a {@code long}.
 */
public final class Fees {
	private Fees() {
	}

	/** {@code percent} % of {@code amount}, rounded half up; {@code 0} for a zero or negative amount. */
	public static long percentOf(long amount, double percent) {
		if (amount <= 0 || percent <= 0) {
			return 0;
		}
		BigDecimal share = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
		return Math.min(amount, share.longValue());
	}

	/** What the seller gets for a sale at {@code price}, after the sales tax. */
	public static long proceeds(long price, double taxPercent) {
		return Math.max(0, price - percentOf(price, taxPercent));
	}

	/** {@code unitPrice * count}, or {@code -1} if that does not fit in a {@code long}. */
	public static long total(long unitPrice, int count) {
		try {
			return Math.multiplyExact(unitPrice, (long) count);
		} catch (ArithmeticException e) {
			return -1;
		}
	}
}
