package com.mejta.mejtadiscord.text;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes text safe to cross between Minecraft and Discord: no mass mentions, no formatting codes, no
 * control characters, bounded length. Outgoing messages also disable mentions in the request itself
 * ({@code allowed_mentions}); this is the second layer, so nothing even looks like a ping.
 */
public final class Sanitizer {
	private static final String ZWSP = "\u200B"; // zero-width space
	private static final Pattern SECTION_CODES = Pattern.compile("§.?");
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");
	/** For Minecraft: also private-use, unassigned and lone surrogate code points, which render as boxes or nothing. */
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Cs}]");
	/** Whitespace and characters that render blank (Braille blank, Hangul fillers), so a name or message cannot look empty or padded. */
	private static final Pattern BLANK = Pattern.compile("[\\s\\p{Z}\\u2800\\u3164\\u115F\\u1160\\uFFA0\\u3000]+");
	private static final Pattern DISCORD_MENTION = Pattern.compile("<(@[!&]?|#)\\d{5,25}>");
	/** Formatting characters, plus brackets so a player cannot post masked links. URLs stay clickable. */
	private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|>\\[\\]])");
	/** A leading # or -# would make a heading or subtext. */
	private static final Pattern LINE_START = Pattern.compile("^(\\s*)(-?#)");

	private Sanitizer() {
	}

	/** Minecraft text for Discord: mass mentions and raw mention syntax neutralised, markdown escaped. */
	public static String forDiscord(String text, int maxLength) {
		String out = CONTROL.matcher(SECTION_CODES.matcher(text).replaceAll("")).replaceAll("");
		out = neutraliseMentions(out); // first: escaping '>' would hide a mention from the pattern
		out = MARKDOWN.matcher(out).replaceAll("\\\\$1");
		out = LINE_START.matcher(out).replaceAll("$1\\\\$2");
		return limit(out, maxLength);
	}

	/** Like {@link #forDiscord} but keeps markdown (for embed text MejtaDiscord itself builds). */
	public static String plainForDiscord(String text, int maxLength) {
		String out = CONTROL.matcher(SECTION_CODES.matcher(text).replaceAll("")).replaceAll("");
		return limit(neutraliseMentions(out), maxLength);
	}

	static String neutraliseMentions(String text) {
		String out = text.replace("@everyone", "@" + ZWSP + "everyone").replace("@here", "@" + ZWSP + "here");
		return DISCORD_MENTION.matcher(out).replaceAll(m -> Matcher.quoteReplacement("<" + ZWSP + m.group().substring(1)));
	}

	/**
	 * Discord text for Minecraft: one line, no control, invisible or unassigned characters or § codes,
	 * runs of whitespace and blank-looking characters collapsed to one space, bounded, and with the word
	 * filter applied (each listed word becomes asterisks, case-insensitively).
	 */
	public static String forMinecraft(String text, int maxLength, List<String> filteredWords) {
		String out = BLANK.matcher(text).replaceAll(" "); // first: line breaks and tabs are controls, but separate words
		out = INVISIBLE.matcher(out).replaceAll("");
		out = SECTION_CODES.matcher(out).replaceAll(""); // the code letter too: "§chello" is "hello", not "chello"
		out = BLANK.matcher(out).replaceAll(" "); // again: removing characters can join two runs
		for (String word : filteredWords) {
			if (!word.isBlank()) {
				out = Pattern.compile(Pattern.quote(word.strip()), Pattern.CASE_INSENSITIVE).matcher(out)
						.replaceAll("*".repeat(word.strip().length()));
			}
		}
		return limit(out.strip(), maxLength);
	}

	public static String limit(String text, int maxLength) {
		if (text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, Math.max(0, maxLength - 1)) + "…";
	}

	/** Words from a comma-separated setting. */
	public static List<String> words(String list) {
		return java.util.Arrays.stream(list.split(",")).map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).toList();
	}
}
