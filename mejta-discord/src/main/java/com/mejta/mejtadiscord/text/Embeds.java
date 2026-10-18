package com.mejta.mejtadiscord.text;

import java.time.Instant;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds Discord webhook payloads with one embed, within Discord's limits (title 256, description
 * 4096, 25 fields with names of 256 and values of 1024, footer 2048, 6000 characters in total).
 * Text from players (names, reasons) is markdown-escaped and cannot mention anyone.
 */
public final class Embeds {
	public static final int TITLE = 256;
	public static final int DESCRIPTION = 4096;
	public static final int FIELDS = 25;
	public static final int FIELD_NAME = 256;
	public static final int FIELD_VALUE = 1024;
	public static final int FOOTER = 2048;
	public static final int TOTAL = 6000;
	public static final int USERNAME = 80;

	/** Colours by severity name: INFO blue, WARNING orange, CRITICAL red. */
	public static int color(String severity) {
		return switch (severity) {
			case "CRITICAL" -> 0xE74C3C;
			case "WARNING" -> 0xE67E22;
			default -> 0x3498DB;
		};
	}

	private Embeds() {
	}

	/**
	 * @param fields shown in order; empty values are skipped and at most 25 are shown
	 * @param footer for example the notification channel
	 */
	public static JsonObject payload(String username, String avatarUrl, String title, String description, String severity,
			Map<String, String> fields, String footer, Instant timestamp) {
		JsonObject embed = new JsonObject();
		int budget = TOTAL;
		String t = Sanitizer.plainForDiscord(title, TITLE);
		budget -= t.length();
		if (!t.isEmpty()) {
			embed.addProperty("title", t);
		}
		String f = Sanitizer.plainForDiscord(footer, Math.min(FOOTER, 200));
		budget -= f.length();
		String d = Sanitizer.forDiscord(description, Math.min(DESCRIPTION, Math.max(0, budget - 1000)));
		budget -= d.length();
		if (!d.isEmpty()) {
			embed.addProperty("description", d);
		}
		embed.addProperty("color", color(severity));
		JsonArray list = new JsonArray();
		for (Map.Entry<String, String> e : fields.entrySet()) {
			if (list.size() >= FIELDS) {
				break;
			}
			String name = Sanitizer.plainForDiscord(e.getKey(), 64);
			String value = Sanitizer.forDiscord(e.getValue() == null ? "" : e.getValue(), Math.min(FIELD_VALUE, 256));
			if (name.isBlank() || value.isBlank()) {
				continue;
			}
			if (name.length() + value.length() > budget) {
				break;
			}
			budget -= name.length() + value.length();
			JsonObject field = new JsonObject();
			field.addProperty("name", name);
			field.addProperty("value", value);
			field.addProperty("inline", value.length() <= 40);
			list.add(field);
		}
		if (!list.isEmpty()) {
			embed.add("fields", list);
		}
		if (!f.isEmpty()) {
			JsonObject foot = new JsonObject();
			foot.addProperty("text", f);
			embed.add("footer", foot);
		}
		embed.addProperty("timestamp", timestamp.toString());
		JsonObject payload = new JsonObject();
		putIdentity(payload, username, avatarUrl);
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		return payload;
	}

	/** A plain message (chat relay): markdown escaped, mentions neutralised, at most 2000 characters. */
	public static JsonObject text(String username, String avatarUrl, String content) {
		JsonObject payload = new JsonObject();
		putIdentity(payload, username, avatarUrl);
		payload.addProperty("content", content);
		return payload;
	}

	private static void putIdentity(JsonObject payload, String username, String avatarUrl) {
		String name = webhookUsername(username);
		if (!name.isEmpty()) {
			payload.addProperty("username", name);
		}
		if (avatarUrl != null && !avatarUrl.isEmpty()) {
			payload.addProperty("avatar_url", avatarUrl);
		}
	}

	/**
	 * Discord refuses webhook names containing "discord" or "clyde" and names over 80 characters; such a
	 * name would make every message fail, so it is adjusted instead.
	 */
	public static String webhookUsername(String name) {
		if (name == null) {
			return "";
		}
		String out = Sanitizer.plainForDiscord(name.replace('\n', ' '), USERNAME).strip();
		out = out.replaceAll("(?i)discord", "Dlscord").replaceAll("(?i)clyde", "Clyd3");
		return out;
	}
}
