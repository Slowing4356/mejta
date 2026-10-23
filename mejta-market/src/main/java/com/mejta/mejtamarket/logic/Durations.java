package com.mejta.mejtamarket.logic;

/** Short time spans for menus: {@code 2d 5h}, {@code 3h 10m}, {@code 45m}, {@code <1m}. */
public final class Durations {
	private Durations() {
	}

	public static String shortly(long millis) {
		if (millis < 60_000L) {
			return "<1m";
		}
		long minutes = millis / 60_000L;
		long days = minutes / 1440;
		long hours = minutes / 60 % 24;
		long mins = minutes % 60;
		if (days > 0) {
			return days + "d" + (hours > 0 ? " " + hours + "h" : "");
		}
		if (hours > 0) {
			return hours + "h" + (mins > 0 ? " " + mins + "m" : "");
		}
		return mins + "m";
	}
}
