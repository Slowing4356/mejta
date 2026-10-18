package com.mejta.mejtadiscord.text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills message templates such as {@code "[Discord] {user}: {message}"}. Substitution is a single
 * pass, so text that itself contains {@code {user}} is never expanded again. Unknown placeholders are
 * left as they are.
 */
public final class ChatFormat {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

	private ChatFormat() {
	}

	public static String fill(String template, Map<String, String> values) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** Templates must contain {@code {message}}, so no setting can silently hide the text. */
	public static boolean isMessageTemplate(String template) {
		return template.length() <= 200 && template.contains("{message}");
	}
}
