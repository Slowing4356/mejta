package com.mejta.mejtautils.freeze;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Decides which commands a frozen player may still run. */
public final class CommandFilter {
	private final Set<String> allowed;

	private CommandFilter(Set<String> allowed) {
		this.allowed = allowed;
	}

	/** @param list comma-separated command names, e.g. {@code "msg, tell, w, r"} */
	public static CommandFilter parse(String list) {
		Set<String> names = new HashSet<>();
		for (String part : list.split(",")) {
			String name = normalize(part);
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return new CommandFilter(Set.copyOf(names));
	}

	/** @param commandLine with or without the leading slash, e.g. {@code "/msg Mod hi"} */
	public boolean isAllowed(String commandLine) {
		String trimmed = commandLine.strip();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		int space = trimmed.indexOf(' ');
		String root = normalize(space < 0 ? trimmed : trimmed.substring(0, space));
		return !root.isEmpty() && allowed.contains(root);
	}

	/** Lowercase, without a slash or namespace ({@code minecraft:msg} counts as {@code msg}). */
	private static String normalize(String name) {
		String out = name.strip().toLowerCase(Locale.ROOT);
		if (out.startsWith("/")) {
			out = out.substring(1);
		}
		int colon = out.indexOf(':');
		return colon >= 0 ? out.substring(colon + 1) : out;
	}
}
