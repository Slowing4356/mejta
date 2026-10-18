package com.mejta.mejtacore.api.notify;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * An immutable event that an addon publishes for delivery by {@link NotificationProvider}s
 * (in-game staff chat, and later Discord or webhooks through other mods).
 *
 * <pre>{@code
 * MejtaCoreAPI.notifications().publish(Notification.builder("myaddon", "myaddon.alert")
 *     .severity(Severity.WARNING)
 *     .title("Something happened")
 *     .message("Steve did a thing")
 *     .field("player", "Steve")
 *     .inGameAudience(MyAddon.ALERTS)
 *     .build());
 * }</pre>
 *
 * <p>Everything in a notification may be sent to external services by providers the server owner
 * has configured. Never put passwords, tokens, IP addresses or other secrets in it.
 */
public final class Notification {
	/** Channels: {@code <addon id>.<name>}, lowercase, e.g. {@code mejtaac.alert}. */
	public static final Pattern CHANNEL_PATTERN = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+");

	private static final int MAX_FIELDS = 32;
	private static final int MAX_TEXT = 2000;

	private final String source;
	private final String channel;
	private final Severity severity;
	private final String title;
	private final String message;
	private final Map<String, String> fields;
	private final Instant timestamp;
	private final MejtaPermission inGameAudience;

	private Notification(Builder b) {
		this.source = b.source;
		this.channel = b.channel;
		this.severity = b.severity;
		this.title = b.title;
		this.message = b.message;
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
		this.timestamp = b.timestamp == null ? Instant.now() : b.timestamp;
		this.inGameAudience = b.inGameAudience;
	}

	/**
	 * @param source  ID of the publishing addon
	 * @param channel routing key, e.g. {@code mejtaac.alert}; providers decide where each channel goes
	 */
	public static Builder builder(String source, String channel) {
		return new Builder(source, channel);
	}

	public String source() {
		return source;
	}

	public String channel() {
		return channel;
	}

	public Severity severity() {
		return severity;
	}

	public String title() {
		return title;
	}

	public String message() {
		return message;
	}

	/** Structured details in insertion order (e.g. player, check, value). */
	public Map<String, String> fields() {
		return fields;
	}

	public Instant timestamp() {
		return timestamp;
	}

	/**
	 * Who may see this in in-game chat. Empty means the in-game provider does not show it at all
	 * (external providers still receive it).
	 */
	public Optional<MejtaPermission> inGameAudience() {
		return Optional.ofNullable(inGameAudience);
	}

	@Override
	public String toString() {
		return "Notification[" + channel + " " + severity + " " + title + "]";
	}

	public static final class Builder {
		private final String source;
		private final String channel;
		private Severity severity = Severity.INFO;
		private String title = "";
		private String message = "";
		private final Map<String, String> fields = new LinkedHashMap<>();
		private Instant timestamp;
		private MejtaPermission inGameAudience;

		private Builder(String source, String channel) {
			this.source = Objects.requireNonNull(source, "source");
			Objects.requireNonNull(channel, "channel");
			if (channel.length() > 64 || !CHANNEL_PATTERN.matcher(channel).matches()) {
				throw new IllegalArgumentException("Invalid channel '" + channel + "': must look like 'addonid.name'");
			}
			this.channel = channel;
		}

		public Builder severity(Severity severity) {
			this.severity = Objects.requireNonNull(severity, "severity");
			return this;
		}

		public Builder title(String title) {
			this.title = limit(Objects.requireNonNull(title, "title"));
			return this;
		}

		public Builder message(String message) {
			this.message = limit(Objects.requireNonNull(message, "message"));
			return this;
		}

		/** Adds a structured detail. At most 32 fields; extra fields are ignored. */
		public Builder field(String key, Object value) {
			Objects.requireNonNull(key, "key");
			if (fields.size() < MAX_FIELDS || fields.containsKey(key)) {
				fields.put(key, limit(String.valueOf(value)));
			}
			return this;
		}

		public Builder timestamp(Instant timestamp) {
			this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
			return this;
		}

		/** Shows the notification in chat to online players who have {@code permission}. */
		public Builder inGameAudience(MejtaPermission permission) {
			this.inGameAudience = Objects.requireNonNull(permission, "permission");
			return this;
		}

		public Notification build() {
			return new Notification(this);
		}

		private static String limit(String text) {
			return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "...";
		}
	}
}
