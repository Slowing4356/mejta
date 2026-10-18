package com.mejta.mejtadiscord;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtadiscord.text.ChatFormat;
import com.mejta.mejtadiscord.text.Routing;
import com.mejta.mejtadiscord.text.WebhookUrls;

/**
 * MejtaDiscord's settings, in {@code config/mejta/mejtadiscord.json}. Keys are stable. Webhook URLs and
 * the bot token are secrets: MejtaCore stores them in {@code config/mejta/secrets/mejtadiscord.json}
 * (owner-only), never shows them, and only lets holders of {@code mejtacore.config.secrets} set them.
 */
public final class DiscordSettings {
	/** Notification webhooks by the name used in {@code notify.routing}. Declared first: validators below use it. */
	public static final java.util.List<String> WEBHOOK_NAMES = java.util.List.of("default", "alerts", "staff");

	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
			.describedAs("Master switch. With no webhook URL or bot token set, MejtaDiscord sends and receives nothing.");

	// ---------------------------------------------------------------- notifications (anti-cheat alerts)
	public static final Setting<String> WEBHOOK_DEFAULT = webhook("webhook.default.url",
			"Webhook for notifications routed to 'default' (see notify.routing)");
	public static final Setting<String> WEBHOOK_ALERTS = webhook("webhook.alerts.url",
			"Webhook for notifications routed to 'alerts'");
	public static final Setting<String> WEBHOOK_STAFF = webhook("webhook.staff.url",
			"Webhook for notifications routed to 'staff'");
	public static final Setting<String> NOTIFY_ROUTING = Setting.string("notify.routing", "mejtaac.*=default")
			.validatedBy(DiscordSettings::isValidRouting)
			.describedAs("Which notification channels go to which webhook: 'channel=webhook' rules separated by ';', first match wins. "
					+ "Webhooks: default, alerts, staff, or none. Example: 'mejtaac.enforcement=staff; mejtaac.*=alerts'. "
					+ "Channels with no matching rule are not sent.");
	public static final Setting<Severity> NOTIFY_MIN_SEVERITY = Setting.enumeration("notify.min_severity", Severity.INFO)
			.describedAs("Notifications below this severity are not sent to Discord");
	public static final Setting<String> NOTIFY_USERNAME = Setting.string("notify.username", "Mejta")
			.validatedBy(text -> !text.isBlank() && text.length() <= 80)
			.describedAs("Name shown on notification messages");
	public static final Setting<String> NOTIFY_AVATAR = Setting.string("notify.avatar_url", "")
			.validatedBy(WebhookUrls::isAvatarTemplate)
			.describedAs("Picture shown on notification messages (HTTPS URL); empty uses the webhook's own");

	// ---------------------------------------------------------------- Minecraft chat to Discord
	public static final Setting<Boolean> CHAT_TO_DISCORD = Setting.bool("chat.to_discord.enabled", true)
			.describedAs("Send Minecraft chat to Discord through chat.webhook.url");
	public static final Setting<String> CHAT_WEBHOOK = webhook("chat.webhook.url",
			"Webhook that posts Minecraft chat into the chat channel");
	public static final Setting<String> CHAT_USERNAME = Setting.string("chat.to_discord.username", "{player}")
			.validatedBy(text -> !text.isBlank() && text.length() <= 80)
			.describedAs("Name shown on relayed chat messages; {player} is the player's name");
	public static final Setting<String> CHAT_AVATAR = Setting.string("chat.to_discord.avatar_url", "")
			.validatedBy(WebhookUrls::isAvatarTemplate)
			.describedAs("Picture shown on relayed chat messages, with {uuid} and {player} placeholders, for example a skin-head "
					+ "service. Discord fetches it, so the player's UUID or name reaches that service. Empty: the webhook's own picture.");
	public static final Setting<Boolean> CHAT_JOIN_LEAVE = Setting.bool("chat.to_discord.join_leave", false)
			.describedAs("Also post join and leave messages. Vanished staff are never announced.");

	// ---------------------------------------------------------------- Discord chat to Minecraft
	public static final Setting<Boolean> CHAT_TO_MINECRAFT = Setting.bool("chat.to_minecraft.enabled", true).experimental()
			.describedAs("Show messages from the Discord chat channel in Minecraft. Needs bot.token and chat.to_minecraft.channel_id, "
					+ "and the bot's Message Content intent enabled in the Discord Developer Portal.");
	public static final Setting<String> BOT_TOKEN = Setting.string("bot.token", "")
			.validatedBy(WebhookUrls::isTokenShapeOrEmpty)
			.secret()
			.describedAs("Discord bot token (Developer Portal > Bot > Reset Token), without 'Bot '. Only used to read the chat channel.");
	public static final Setting<String> CHAT_CHANNEL = Setting.string("chat.to_minecraft.channel_id", "")
			.validatedBy(WebhookUrls::isSnowflakeOrEmpty)
			.describedAs("ID of the Discord channel relayed into Minecraft (Developer Mode > right-click the channel > Copy Channel ID)");
	public static final Setting<String> CHAT_FORMAT = Setting.string("chat.to_minecraft.format", "[Discord] {user}: {message}")
			.validatedBy(ChatFormat::isMessageTemplate)
			.describedAs("How Discord messages look in Minecraft; {user} and {message} are filled in. Must contain {message}.");
	public static final Setting<Integer> CHAT_MAX_LENGTH = Setting.integer("chat.to_minecraft.max_length", 256, 16, 1000)
			.describedAs("Longer Discord messages are cut to this many characters");
	public static final Setting<Integer> CHAT_RATE = Setting.integer("chat.to_minecraft.per_user_per_minute", 10, 1, 120)
			.describedAs("Messages one Discord user can relay per minute; the rest are not shown");
	public static final Setting<String> CHAT_WORD_FILTER = Setting.string("chat.to_minecraft.word_filter", "")
			.validatedBy(text -> text.length() <= 2000)
			.describedAs("Comma-separated words replaced by asterisks in Discord messages shown in Minecraft");

	// ---------------------------------------------------------------- delivery
	public static final Setting<Integer> MAX_ATTEMPTS = Setting.integer("delivery.max_attempts", 5, 1, 10)
			.describedAs("Attempts per message when Discord or the network fails, before it is dropped (rate limits are always waited out)");
	public static final Setting<Integer> QUEUE_SIZE = Setting.integer("delivery.queue_size", 200, 10, 5000)
			.describedAs("Messages waiting per webhook; when full, routine messages are dropped before important ones");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ENABLED, WEBHOOK_DEFAULT, WEBHOOK_ALERTS, WEBHOOK_STAFF, NOTIFY_ROUTING, NOTIFY_MIN_SEVERITY, NOTIFY_USERNAME, NOTIFY_AVATAR,
					CHAT_TO_DISCORD, CHAT_WEBHOOK, CHAT_USERNAME, CHAT_AVATAR, CHAT_JOIN_LEAVE,
					CHAT_TO_MINECRAFT, BOT_TOKEN, CHAT_CHANNEL, CHAT_FORMAT, CHAT_MAX_LENGTH, CHAT_RATE, CHAT_WORD_FILTER,
					MAX_ATTEMPTS, QUEUE_SIZE)
			.build();

	static boolean isValidRouting(String text) {
		Routing routing = Routing.parse(text);
		return text.length() <= 500 && routing.problems().isEmpty() && WEBHOOK_NAMES.containsAll(routing.targets());
	}

	private static Setting<String> webhook(String key, String description) {
		return Setting.string(key, "")
				.validatedBy(WebhookUrls::isValidOrEmpty)
				.secret()
				.describedAs(description + ". A Discord webhook URL (Channel settings > Integrations > Webhooks > Copy URL).");
	}

	private DiscordSettings() {
	}
}
