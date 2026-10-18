package com.mejta.mejtadiscord.text;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

/**
 * Removes secrets from any text before it is logged: webhook URLs, bot tokens, and the configured
 * secret values themselves. Every log line and diagnostic of MejtaDiscord passes through here;
 * raw exception messages are never logged, because the HTTP client puts request URLs into them.
 */
public final class Redactor {
	private static final Pattern WEBHOOK = Pattern.compile(
			"(?i)https?://(?:[a-z]+\\.)?(?:discord|discordapp)\\.com/api(?:/v\\d+)?/webhooks/[^\\s\"'<>]+");
	/** Discord bot tokens: three base64url parts separated by dots. */
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}");
	private static final Pattern BOT_HEADER = Pattern.compile("(?i)(Bot\\s+)[^\\s\"']+");

	private final Set<String> secrets = new CopyOnWriteArraySet<>();

	/** Also hides these exact values (the configured token and webhook URLs). Empty values are ignored. */
	public void setSecrets(List<String> values) {
		secrets.clear();
		for (String value : values) {
			if (value != null && value.length() >= 8) {
				secrets.add(value);
			}
		}
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
