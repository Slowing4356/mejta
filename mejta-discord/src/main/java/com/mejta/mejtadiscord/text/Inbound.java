package com.mejta.mejtadiscord.text;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A Discord message to show in Minecraft, read from a gateway {@code MESSAGE_CREATE}. Only plain
 * messages from people in the configured channel are relayed: bots, webhooks (including our own chat
 * relay, which would echo every message) and system messages are ignored.
 *
 * @param userId  the author's Discord ID, for rate limiting
 * @param name    server nickname, else global display name, else username
 * @param content Discord markup turned into readable text: mentions become {@code @name}, custom emoji
 *                {@code :name:}; attachments and stickers are noted, never linked
 */
public record Inbound(String userId, String name, String content) {
	private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{5,25})>");
	private static final Pattern ROLE_MENTION = Pattern.compile("<@&\\d{5,25}>");
	private static final Pattern CHANNEL_MENTION = Pattern.compile("<#\\d{5,25}>");
	private static final Pattern EMOJI = Pattern.compile("<a?:(\\w{1,32}):\\d{5,25}>");
	private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d{1,13})(?::[tTdDfFR])?>");

	/** Message types: 0 default, 19 reply. Everything else (joins, pins, boosts) is a system message. */
	private static boolean isUserMessageType(int type) {
		return type == 0 || type == 19;
	}

	public static Optional<Inbound> from(JsonObject d, String channelId, String botUserId) {
		if (channelId.isEmpty() || !channelId.equals(string(d, "channel_id"))) {
			return Optional.empty();
		}
		if (d.has("webhook_id") && !d.get("webhook_id").isJsonNull()) {
			return Optional.empty();
		}
		if (d.has("type") && !isUserMessageType(d.get("type").getAsInt())) {
			return Optional.empty();
		}
		JsonObject author = object(d, "author");
		if (author == null) {
			return Optional.empty();
		}
		String userId = string(author, "id");
		if (userId.isEmpty() || userId.equals(botUserId) || bool(author, "bot") || bool(author, "system")) {
			return Optional.empty();
		}
		String name = string(object(d, "member"), "nick");
		if (name.isEmpty()) {
			name = string(author, "global_name");
		}
		if (name.isEmpty()) {
			name = string(author, "username");
		}
		String content = readable(string(d, "content"), mentionNames(d));
		int attachments = d.has("attachments") && d.get("attachments").isJsonArray() ? d.getAsJsonArray("attachments").size() : 0;
		if (attachments > 0) {
			content = (content + " [" + attachments + (attachments == 1 ? " attachment]" : " attachments]")).strip();
		}
		if (d.has("sticker_items") && d.get("sticker_items").isJsonArray() && !d.getAsJsonArray("sticker_items").isEmpty()) {
			content = (content + " [sticker]").strip();
		}
		if (content.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new Inbound(userId, name, content));
	}

	private static Map<String, String> mentionNames(JsonObject d) {
		Map<String, String> names = new HashMap<>();
		if (d.has("mentions") && d.get("mentions").isJsonArray()) {
			for (JsonElement e : d.getAsJsonArray("mentions")) {
				if (e.isJsonObject()) {
					JsonObject user = e.getAsJsonObject();
					String name = string(object(user, "member"), "nick");
					if (name.isEmpty()) {
						name = string(user, "global_name");
					}
					if (name.isEmpty()) {
						name = string(user, "username");
					}
					names.put(string(user, "id"), name);
				}
			}
		}
		return names;
	}

	static String readable(String content, Map<String, String> names) {
		String out = USER_MENTION.matcher(content).replaceAll(m -> Matcher.quoteReplacement("@" + names.getOrDefault(m.group(1), "user")));
		out = ROLE_MENTION.matcher(out).replaceAll("@role");
		out = CHANNEL_MENTION.matcher(out).replaceAll("#channel");
		out = EMOJI.matcher(out).replaceAll(":$1:");
		out = TIMESTAMP.matcher(out).replaceAll(m -> {
			try {
				return Matcher.quoteReplacement(java.time.Instant.ofEpochSecond(Long.parseLong(m.group(1))).toString());
			} catch (RuntimeException e) {
				return "(time)";
			}
		});
		return out;
	}

	private static JsonObject object(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	private static String string(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
	}
}
