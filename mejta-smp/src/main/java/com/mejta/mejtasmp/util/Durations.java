package com.mejta.mejtasmp.util;

/** Short human-readable durations for chat messages. */
public final class Durations {
	private Durations() {
	}

	/** {@code 45s}, {@code 2m 05s}, {@code 1h 02m}; anything below one second is {@code 1s}. */
	public static String format(long millis) {
		long seconds = Math.max(1, (millis + 999) / 1000);
		if (seconds < 60) {
			return seconds + "s";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m " + String.format(java.util.Locale.ROOT, "%02ds", seconds % 60);
		}
		return minutes / 60 + "h " + String.format(java.util.Locale.ROOT, "%02dm", minutes % 60);
	}
}
