package com.mejta.mejtacore.logging;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects problems (rejected registrations, config errors, failed addons) so they can be shown
 * in-game via {@code /mejtacore addons} and the menu, not only in the log file.
 */
public final class Diagnostics {
	public enum Level {
		WARNING,
		ERROR
	}

	public record Entry(Level level, String subject, String message) {
	}

	private static final int MAX_ENTRIES = 200;
	private static final List<Entry> ENTRIES = new ArrayList<>();

	private Diagnostics() {
	}

	public static void warn(String subject, String message) {
		MejtaLog.CORE.warn("[{}] {}", subject, message);
		add(new Entry(Level.WARNING, subject, message));
	}

	public static void error(String subject, String message) {
		MejtaLog.CORE.error("[{}] {}", subject, message);
		add(new Entry(Level.ERROR, subject, message));
	}

	public static void error(String subject, String message, Throwable t) {
		MejtaLog.CORE.error("[{}] {}", subject, message, t);
		add(new Entry(Level.ERROR, subject, message + " (" + t + ")"));
	}

	private static synchronized void add(Entry entry) {
		if (ENTRIES.size() >= MAX_ENTRIES) {
			ENTRIES.remove(0);
		}
		ENTRIES.add(entry);
	}

	public static synchronized List<Entry> entries() {
		return List.copyOf(ENTRIES);
	}
}
