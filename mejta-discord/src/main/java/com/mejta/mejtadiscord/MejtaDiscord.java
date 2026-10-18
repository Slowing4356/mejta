package com.mejta.mejtadiscord;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtadiscord.net.Bridge;
import com.mejta.mejtadiscord.net.GatewayClient;
import com.mejta.mejtadiscord.text.Embeds;
import com.mejta.mejtadiscord.text.Inbound;
import com.mejta.mejtadiscord.text.Redactor;
import com.mejta.mejtadiscord.text.Routing;
import com.mejta.mejtadiscord.text.Sanitizer;

/**
 * MejtaDiscord entrypoint. Sends MejtaCore notifications (MejtaAC alerts) to Discord webhooks and
 * relays chat both ways. It talks to MejtaAC only through MejtaCore's notification service, and to
 * Discord only through webhooks (outgoing) and a read-only gateway connection (incoming chat).
 *
 * <p>Connections live from server start to server stop. With nothing configured it does nothing.
 */
public final class MejtaDiscord implements MejtaAddon {
	public static final String ID = "mejtadiscord";
	/** MejtaDiscord's own warnings (a deleted webhook, a refused bot token); never sent to Discord. */
	public static final String CHANNEL_STATUS = ID + ".status";

	private static final URI GATEWAY = URI.create("wss://gateway.discord.gg");

	/** Settings read once per change, so the notification, gateway and server threads never read the config file. */
	record Snapshot(boolean enabled, Routing routing, Severity minSeverity, String notifyUsername, String notifyAvatar,
			boolean chatToDiscord, String chatUsername, String chatAvatar, boolean joinLeave,
			boolean chatToMinecraft, String minecraftFormat, int maxLength, int ratePerMinute, int globalPerMinute,
			List<String> filteredWords) {
	}

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile Snapshot snapshot;
	private static volatile Bridge bridge;
	/** Created with the bridge and shut down after it, so no HTTP client threads outlive a server run. */
	private static volatile HttpClient http;
	private static volatile MinecraftServer server;
	private static final DiscordNotifications NOTIFICATIONS = new DiscordNotifications();
	private static final ChatRelay RELAY = new ChatRelay();

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaDiscord")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Anti-cheat alerts to Discord, and chat between Discord and Minecraft")
				.icon("minecraft:bell")
				.config(DiscordSettings.SPEC)
				.permissions(DiscordPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					snapshot = readSnapshot();
					config.addChangeListener(MejtaDiscord::settingsChanged);
					MejtaCoreAPI.notifications().registerProvider(NOTIFICATIONS);
					MejtaCoreAPI.registerServerCommands(ID, MejtaDiscord::adminCommands);
					RELAY.register();
					ServerLifecycleEvents.SERVER_STARTED.register(MejtaDiscord::serverStarted);
					ServerLifecycleEvents.SERVER_STOPPING.register(s -> server = null);
					ServerLifecycleEvents.SERVER_STOPPED.register(s -> stopBridge());
					log.info("MejtaDiscord initialized");
				})
				.build());
	}

	// ---------------------------------------------------------------- lifecycle

	private static void serverStarted(MinecraftServer started) {
		server = started;
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.proxy(ProxySelector.getDefault())
				.build();
		http = client;
		Bridge b = new Bridge(client, new Redactor(), log, new BridgeEvents(), GATEWAY, 1000);
		b.apply(bridgeConfig());
		bridge = b;
		String status = String.join("; ", b.statusLines());
		log.info("MejtaDiscord: {}", status);
	}

	private static void stopBridge() {
		Bridge b = bridge;
		bridge = null;
		if (b != null) {
			b.close(Duration.ofSeconds(3)); // gives the last alerts (for example kicks during shutdown) a moment to go out
		}
		HttpClient client = http;
		http = null;
		if (client != null) {
			client.shutdownNow(); // after the bridge: anything still in flight has had its chance
		}
	}

	private static void settingsChanged() {
		snapshot = readSnapshot();
		NOTIFICATIONS.settingsChanged();
		Bridge b = bridge;
		if (b != null) {
			b.apply(bridgeConfig());
		}
	}

	private static Snapshot readSnapshot() {
		ConfigHandle c = config;
		return new Snapshot(c.get(DiscordSettings.ENABLED), Routing.parse(c.get(DiscordSettings.NOTIFY_ROUTING)),
				c.get(DiscordSettings.NOTIFY_MIN_SEVERITY), c.get(DiscordSettings.NOTIFY_USERNAME), c.get(DiscordSettings.NOTIFY_AVATAR),
				c.get(DiscordSettings.CHAT_TO_DISCORD), c.get(DiscordSettings.CHAT_USERNAME), c.get(DiscordSettings.CHAT_AVATAR),
				c.get(DiscordSettings.CHAT_JOIN_LEAVE), c.get(DiscordSettings.CHAT_TO_MINECRAFT), c.get(DiscordSettings.CHAT_FORMAT),
				c.get(DiscordSettings.CHAT_MAX_LENGTH), c.get(DiscordSettings.CHAT_RATE), c.get(DiscordSettings.CHAT_GLOBAL_RATE),
				Sanitizer.words(c.get(DiscordSettings.CHAT_WORD_FILTER)));
	}

	private static Bridge.Config bridgeConfig() {
		ConfigHandle c = config;
		Map<String, String> webhooks = new LinkedHashMap<>();
		webhooks.put("default", c.get(DiscordSettings.WEBHOOK_DEFAULT));
		webhooks.put("alerts", c.get(DiscordSettings.WEBHOOK_ALERTS));
		webhooks.put("staff", c.get(DiscordSettings.WEBHOOK_STAFF));
		// The chat webhook is passed even when chat.to_discord is off, so it is still redacted from logs.
		return new Bridge.Config(c.get(DiscordSettings.ENABLED), webhooks, c.get(DiscordSettings.CHAT_WEBHOOK),
				c.get(DiscordSettings.CHAT_TO_DISCORD), c.get(DiscordSettings.CHAT_TO_MINECRAFT), c.get(DiscordSettings.BOT_TOKEN),
				c.get(DiscordSettings.CHAT_CHANNEL), c.get(DiscordSettings.MAX_ATTEMPTS), c.get(DiscordSettings.QUEUE_SIZE));
	}

	/** Runs on MejtaDiscord's threads. Problems reach admins in game and in the log, never in Discord itself. */
	private static final class BridgeEvents implements Bridge.Events {
		@Override
		public void inbound(Inbound message) {
			RELAY.toMinecraft(message);
		}

		@Override
		public void webhookDisabled(String webhook, int httpStatus) {
			String key = webhook.equals(Bridge.CHAT) ? "chat.webhook.url" : "webhook." + webhook + ".url";
			warnAdmins("Discord webhook '" + webhook + "' stopped",
					"Discord answered " + httpStatus + ": the webhook was deleted or its URL is wrong. Set a new one with "
							+ "/mejtaadmin config set " + ID + " " + key + " <url>, or, if the problem was fixed in Discord, run /mejtaadmin "
							+ ID + " reconnect.");
		}

		@Override
		public void dropped(String webhook, long total) {
			log.warn("{} message(s) for Discord webhook '{}' were dropped so far (queue full or Discord unreachable)", total, webhook);
		}

		@Override
		public void gatewayState(GatewayClient.State state, String detail) {
			if (state == GatewayClient.State.FAILED) {
				warnAdmins("Discord bot stopped", "Discord chat is no longer shown in Minecraft: " + detail
						+ ". Fix the setting, then run /mejtaadmin " + ID + " reconnect.");
			}
		}
	}

	private static void warnAdmins(String title, String message) {
		log.warn("{}: {}", title, message);
		MejtaCoreAPI.notifications().publish(Notification.builder(ID, CHANNEL_STATUS)
				.severity(Severity.WARNING)
				.title(title)
				.message(message)
				.inGameAudience(DiscordPermissions.ADMIN)
				.build());
	}

	// ---------------------------------------------------------------- /mejtaadmin mejtadiscord

	private static void adminCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		root.then(Commands.literal("status")
						.requires(MejtaCoreAPI.requires(DiscordPermissions.STATUS))
						.executes(MejtaDiscord::status))
				.then(Commands.literal("test")
						.requires(MejtaCoreAPI.requires(DiscordPermissions.ADMIN))
						.then(Commands.argument("webhook", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										List.of("default", "alerts", "staff", Bridge.CHAT), builder))
								.executes(MejtaDiscord::test)))
				.then(Commands.literal("reconnect")
						.requires(MejtaCoreAPI.requires(DiscordPermissions.ADMIN))
						.executes(MejtaDiscord::reconnect));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		Bridge b = bridge;
		List<String> lines = b == null ? List.of("Not running.") : b.statusLines();
		ctx.getSource().sendSystemMessage(Component.literal("MejtaDiscord:").withStyle(ChatFormatting.GOLD));
		for (String line : lines) {
			ctx.getSource().sendSystemMessage(Component.literal(" " + line).withStyle(ChatFormatting.GRAY));
		}
		return lines.size();
	}

	private static int test(CommandContext<CommandSourceStack> ctx) {
		String webhook = StringArgumentType.getString(ctx, "webhook");
		Bridge b = bridge;
		Snapshot s = snapshot;
		boolean queued = b != null && s != null && b.send(webhook, Embeds.payload(s.notifyUsername(), s.notifyAvatar(),
				"MejtaDiscord test", "Test message requested by " + ctx.getSource().getTextName() + ".", Severity.INFO.name(), Map.of(),
				ID + ".test", Instant.now()), true);
		MejtaCoreAPI.audit().record(AuditEntry.builder(ID, "discord.test")
				.actor(MejtaCoreAPI.actor(ctx.getSource()))
				.target(webhook)
				.outcome(queued ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
				.build());
		if (!queued) {
			ctx.getSource().sendFailure(Component.literal("Webhook '" + webhook + "' is not set, disabled or busy. See /mejtaadmin " + ID + " status."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Test message queued for '" + webhook + "'. Check Discord, or /mejtaadmin "
				+ ID + " status in a few seconds."), false);
		return 1;
	}

	/** Reconnects the bot and gives stopped (refused) webhooks another try. */
	private static int reconnect(CommandContext<CommandSourceStack> ctx) {
		Bridge b = bridge;
		boolean started = b != null && b.reconnectGateway();
		List<String> reenabled = b == null ? List.of() : b.reenableWebhooks();
		boolean done = started || !reenabled.isEmpty();
		MejtaCoreAPI.audit().record(AuditEntry.builder(ID, "discord.reconnect")
				.actor(MejtaCoreAPI.actor(ctx.getSource()))
				.outcome(done ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
				.build());
		if (!done) {
			ctx.getSource().sendFailure(Component.literal("Nothing to reconnect: the Discord to Minecraft relay is off or not set up "
					+ "(bot.token and chat.to_minecraft.channel_id), and no webhook is stopped."));
			return 0;
		}
		String message = (started ? "Reconnecting the bot to Discord." : "The bot is off or not set up; not reconnected.")
				+ (reenabled.isEmpty() ? " No webhook was stopped." : " Webhooks enabled again: " + String.join(", ", reenabled)
						+ ". If Discord still refuses them, they stop again and you are told.");
		ctx.getSource().sendSuccess(() -> Component.literal(message), true);
		return 1;
	}

	// ---------------------------------------------------------------- accessors

	static Snapshot snapshot() {
		return snapshot;
	}

	static Bridge bridge() {
		return bridge;
	}

	static MinecraftServer server() {
		return server;
	}

	static Logger log() {
		return log;
	}
}
