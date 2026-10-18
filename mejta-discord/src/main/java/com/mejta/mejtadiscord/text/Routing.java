package com.mejta.mejtadiscord.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Notification channel to webhook routes, from a setting such as
 * {@code "mejtaac.enforcement=kicks; mejtaac.*=alerts; *=default"}. The first matching rule wins;
 * {@code prefix.*} matches the prefix and anything below it, {@code *} matches every channel, and the
 * target {@code none} sends a channel nowhere. Channels without a matching rule are not sent.
 */
public final class Routing {
	public static final String NONE = "none";

	private record Rule(String pattern, String target) {
		boolean matches(String channel) {
			if (pattern.equals("*")) {
				return true;
			}
			if (pattern.endsWith(".*")) {
				String prefix = pattern.substring(0, pattern.length() - 2);
				return channel.equals(prefix) || channel.startsWith(prefix + ".");
			}
			return channel.equals(pattern);
		}
	}

	private final List<Rule> rules;
	private final List<String> problems;

	private Routing(List<Rule> rules, List<String> problems) {
		this.rules = rules;
		this.problems = problems;
	}

	public static Routing parse(String text) {
		List<Rule> rules = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (String part : text.split("[;\\n]")) {
			String rule = part.strip();
			if (rule.isEmpty()) {
				continue;
			}
			int eq = rule.indexOf('=');
			if (eq <= 0 || eq == rule.length() - 1) {
				problems.add("'" + rule + "' is not 'channel=webhook'");
				continue;
			}
			String pattern = rule.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT);
			String target = rule.substring(eq + 1).strip().toLowerCase(java.util.Locale.ROOT);
			if (!pattern.matches("\\*|[a-z0-9_.-]+(\\.\\*)?") || !target.matches("[a-z0-9_-]+")) {
				problems.add("'" + rule + "' has invalid characters");
				continue;
			}
			rules.add(new Rule(pattern, target));
		}
		return new Routing(List.copyOf(rules), List.copyOf(problems));
	}

	/** The webhook name for {@code channel}, or empty if it is not sent anywhere. */
	public Optional<String> route(String channel) {
		for (Rule rule : rules) {
			if (rule.matches(channel)) {
				return rule.target().equals(NONE) ? Optional.empty() : Optional.of(rule.target());
			}
		}
		return Optional.empty();
	}

	public List<String> problems() {
		return problems;
	}

	/** Every webhook name the rules send to (not {@code none}). */
	public java.util.Set<String> targets() {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		for (Rule rule : rules) {
			if (!rule.target().equals(NONE)) {
				out.add(rule.target());
			}
		}
		return out;
	}
}
