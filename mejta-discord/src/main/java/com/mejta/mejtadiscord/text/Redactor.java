package com.mejta.mejtadiscord.text;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes secrets from any text before it is logged: webhook URLs, bot tokens, and the configured
 * secret values themselves. Every log line and diagnostic of MejtaDiscord passes through here;
 * raw exception messages are never logged, because the HTTP client puts request URLs into them.
 */
public final class Redactor {
	private static final Pattern WEBHOOK = Pattern.compile(
			"(?i)https?://(?:[a-z]+\\.)?(?:discord|discordapp)\\.com/api(?:/v\\d+)?/webhooks/[^\\s\"'<>]+");
	/** Discord bot tokens: three base64url parts separated by dots, as loose as {@link WebhookUrls#isTokenShapeOrEmpty}. */
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{4,20}\\.[A-Za-z0-9_-]{20,}");
	private static final Pattern BOT_HEADER = Pattern.compile("(?i)(Bot\\s+)[^\\s\"']+");

	/** Replaced as a whole, so a thread scrubbing while the settings change never sees a half-filled list. */
	private volatile List<String> secrets = List.of();

	/** Also hides these exact values (the configured token and webhook URLs). Empty values are ignored. */
	public void setSecrets(List<String> values) {
		// Longest first, so a secret that contains another is replaced whole.
		secrets = values.stream().filter(v -> v != null && v.length() >= 8).distinct()
				.sorted(Comparator.comparingInt(String::length).reversed()).toList();
	}

	public String scrub(String text) {
		if (text == null) {
			return "";
		}
		String out = text;
		for (String secret : secrets) {
			out = out.replace(secret, "<secret>");
		}
		out = WEBHOOK.matcher(out).replaceAll("<webhook>");
		out = TOKEN.matcher(out).replaceAll("<token>");
		out = BOT_HEADER.matcher(out).replaceAll("$1<token>");
		return out;
	}

	/** A safe description of a failure: its type and scrubbed message, never a stack trace with URLs. */
	public String describe(Throwable t) {
		if (t == null) {
			return "";
		}
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return root.getClass().getSimpleName() + (message == null ? "" : ": " + scrub(message));
	}
}
