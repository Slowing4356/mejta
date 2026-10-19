package com.mejta.mejtaqol.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Names of homes and warps, and the comma-separated lists used in settings. */
public final class Names {
	/** Lowercase letters, digits, {@code _} and {@code -}; 1 to 16 characters. */
	public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,16}");
	/** A dimension ID such as {@code minecraft:the_end}. */
	public static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	private Names() {
	}

	/** The name in lowercase if it is valid, else empty. */
	public static Optional<String> normalize(String name) {
		if (name == null) {
			return Optional.empty();
		}
		String lower = name.toLowerCase(Locale.ROOT);
		return NAME.matcher(lower).matches() ? Optional.of(lower) : Optional.empty();
	}

	/**
	 * The entries of a comma-separated list, trimmed and lowercased, without empty entries. A bare name
	 * gets the {@code minecraft:} namespace when {@code namespaced} is set ({@code the_end} becomes
	 * {@code minecraft:the_end}).
	 */
	public static List<String> list(String text, boolean namespaced) {
		List<String> out = new ArrayList<>();
		if (text == null) {
			return out;
		}
		for (String part : text.split(",")) {
			String s = part.trim().toLowerCase(Locale.ROOT);
			if (s.isEmpty()) {
				continue;
			}
			if (namespaced && !s.contains(":")) {
				s = "minecraft:" + s;
			}
			if (!out.contains(s)) {
				out.add(s);
			}
		}
		return out;
	}

	/** Whether every entry of the list is a valid dimension ID (an empty list is valid). */
	public static boolean isDimensionList(String text) {
		if (text == null || text.length() > 512) {
			return false;
		}
		for (String s : list(text, true)) {
			if (!DIMENSION.matcher(s).matches()) {
				return false;
			}
		}
		return true;
	}
}
