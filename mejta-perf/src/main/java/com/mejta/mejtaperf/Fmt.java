package com.mejta.mejtaperf;

import java.util.Locale;

/** Number formatting shared by chat output and reports. Always in the root locale, so reports read the same everywhere. */
public final class Fmt {
	private Fmt() {
	}

	public static String f(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

	/** {@code 19.97}, or {@code -} when unknown. */
	public static String tps(double tps) {
		return Double.isNaN(tps) ? "-" : f("%.1f", tps);
	}

	/** Milliseconds with a precision that suits the size: {@code 0.042}, {@code 3.15}, {@code 48.2}, {@code 1250}. */
	public static String ms(double ms) {
		if (Double.isNaN(ms)) {
			return "-";
		}
		if (ms < 0.1) {
			return f("%.3f", ms);
		}
		if (ms < 10) {
			return f("%.2f", ms);
		}
		return ms < 1000 ? f("%.1f", ms) : f("%.0f", ms);
	}

	/** Bytes as {@code 512 MB} or {@code 3.4 GB}. */
	public static String bytes(long bytes) {
		if (bytes < 0) {
			return "-";
		}
		double mb = bytes / (1024.0 * 1024.0);
		return mb < 1024 ? f("%.0f MB", mb) : f("%.1f GB", mb / 1024.0);
	}

	/** A 0..1 share as {@code 42%}, or {@code -} when unknown (negative). */
	public static String percent(double share) {
		return share < 0 || Double.isNaN(share) ? "-" : f("%.0f%%", share * 100);
	}

	/** Seconds as {@code 45 s}, {@code 3 min 20 s} or {@code 2 h 5 min}. */
	public static String duration(long seconds) {
		if (seconds < 60) {
			return seconds + " s";
		}
		if (seconds < 3600) {
			return seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
		}
		return seconds / 3600 + " h" + (seconds % 3600 / 60 == 0 ? "" : " " + seconds % 3600 / 60 + " min");
	}

	/** {@code 12,345}. */
	public static String count(long n) {
		return f("%,d", n);
	}
}
