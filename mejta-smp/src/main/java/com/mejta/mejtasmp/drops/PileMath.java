package com.mejta.mejtasmp.drops;

/** The arithmetic of piles, kept apart from the game so it can be unit tested. */
public final class PileMath {
	/** How a pile of {@code total} items is held: a visible stack of at most {@code maxStack}, and the rest. */
	public record Split(int visible, int extra) {
	}

	private PileMath() {
	}

	public static Split split(int total, int maxStack) {
		int visible = Math.min(Math.max(total, 0), Math.max(maxStack, 1));
		return new Split(visible, Math.max(total, 0) - visible);
	}

	/** Whether two piles may become one without passing {@code cap}. */
	public static boolean fits(int a, int b, int cap) {
		return a > 0 && b > 0 && (long) a + b <= cap;
	}

	/** {@code 1,000} style counts for the label. */
	public static String format(int total) {
		return String.format(java.util.Locale.ROOT, "%,d", total);
	}
}
