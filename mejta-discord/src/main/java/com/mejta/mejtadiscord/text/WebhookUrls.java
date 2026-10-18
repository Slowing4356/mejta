package com.mejta.mejtadiscord.text;

import java.util.regex.Pattern;

/** Which webhook URLs are accepted: only Discord's own, over HTTPS, so a setting cannot send data elsewhere. */
public final class WebhookUrls {
	private static final Pattern URL = Pattern.compile(
			"https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api(?:/v\\d{1,2})?/webhooks/\\d{5,25}/[A-Za-z0-9_-]{20,100}(?:\\?thread_id=\\d{5,25})?");

	private WebhookUrls() {
	}

	/** Empty (not configured) or a Discord webhook URL. */
	public static boolean isValidOrEmpty(String url) {
		return url.isEmpty() || URL.matcher(url).matches();
	}

	/** Discord snowflake IDs, as used for the chat channel. */
	public static boolean isSnowflakeOrEmpty(String id) {
		return id.isEmpty() || id.matches("\\d{5,25}");
	}

	/** A bot token's shape (three dot-separated parts); empty means not configured. */
	public static boolean isTokenShapeOrEmpty(String token) {
		return token.isEmpty() || token.matches("[A-Za-z0-9_-]{20,100}\\.[A-Za-z0-9_-]{4,20}\\.[A-Za-z0-9_-]{20,200}");
	}

	/** Optional avatar URL template: empty or HTTPS. */
	public static boolean isAvatarTemplate(String url) {
		return url.isEmpty() || url.length() <= 200 && url.matches("https://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%{}-]+");
	}
}
