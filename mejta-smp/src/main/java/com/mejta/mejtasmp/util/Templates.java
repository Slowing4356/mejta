package com.mejta.mejtasmp.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configurable messages: {@code {placeholder}} values and {@code &} colour codes ({@code &a} green,
 * {@code &l} bold, {@code &r} reset; {@code &&} is a literal {@code &}).
 */
public final class Templates {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
	private static final Pattern CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR&])");

	private Templates() {
	}

	/**
	 * Fills in the placeholders (unknown ones stay as written) and turns {@code &} codes into section-sign
	 * codes. Placeholder values are inserted literally: a player name can never add colour codes.
	 */
	public static String render(String template, Map<String, String> values) {
		String coloured = colour(template);
		Matcher m = PLACEHOLDER.matcher(coloured);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = values.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value.replace("\u00a7", "")));
		}
		m.appendTail(out);
		return out.toString();
	}

	static String colour(String text) {
		Matcher m = CODE.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String c = m.group(1);
			m.appendReplacement(out, Matcher.quoteReplacement(c.equals("&") ? "&" : "\u00a7" + c.toLowerCase(java.util.Locale.ROOT)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** Whether a template is acceptable for a setting: not blank, at most {@code max} characters, one line. */
	public static boolean valid(String template, int max) {
		return template != null && !template.isBlank() && template.length() <= max && template.indexOf('\n') < 0
				&& template.indexOf('\u00a7') < 0;
	}
}
