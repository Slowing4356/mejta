package com.mejta.mejtadiscord.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;

import com.google.gson.JsonObject;

import com.mejta.mejtadiscord.text.Inbound;
import com.mejta.mejtadiscord.text.Redactor;

/**
 * Owns MejtaDiscord's connections: one {@link WebhookClient} per configured webhook (notifications and
 * the chat relay) and, when the chat relay to Minecraft is on, one {@link GatewayClient}. Applying new
 * settings rebuilds only what changed, so editing the chat format does not drop queued alerts.
 *
 * <p>Contains no Minecraft code; the addon feeds it settings and receives {@link Events}.
 */
public final class Bridge implements AutoCloseable {
	/** The name of the chat relay's webhook in status output. */
	public static final String CHAT = "chat";

	/**
	 * @param webhooks notification webhooks by name; empty URLs mean not configured
	 * @param token    bot token, empty if the Discord-to-Minecraft relay is not set up
	 */
	public record Config(boolean enabled, Map<String, String> webhooks, String chatWebhook, boolean chatToMinecraft, String token,
			String channelId, int maxAttempts, int queueSize) {
		public Config {
			webhooks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(webhooks)); // keeps the order for status output
		}

		boolean gatewayWanted() {
			return enabled && chatToMinecraft && !token.isEmpty() && !channelId.isEmpty();
		}
	}

	/** Called on MejtaDiscord's own threads, never the server thread: hand work over. */
	public interface Events {
		void inbound(Inbound message);

		default void webhookDisabled(String webhook, int httpStatus) {
		}

		default void dropped(String webhook, long total) {
		}

		default void gatewayState(GatewayClient.State state, String detail) {
		}
	}

	private final HttpClient http;
	private final Redactor redactor;
	private final Logger log;
	private final Events events;
	private final URI gatewayUrl;
	private final long backoffMs;

	private final Map<String, WebhookClient> webhooks = new LinkedHashMap<>();
	private final Map<String, String> urls = new LinkedHashMap<>();
	private Config config;
	private volatile GatewayClient gateway;
	private volatile String channelId = "";
	private boolean closed;

	public Bridge(HttpClient http, Redactor redactor, Logger log, Events events, URI gatewayUrl, long backoffMs) {
		this.http = http;
		this.redactor = redactor;
		this.log = log;
		this.events = events;
		this.gatewayUrl = gatewayUrl;
		this.backoffMs = backoffMs;
	}

	public synchronized void apply(Config next) {
		if (closed) {
			return;
		}
		List<String> secrets = new ArrayList<>(next.webhooks().values());
		secrets.add(next.chatWebhook());
		secrets.add(next.token());
		redactor.setSecrets(secrets);

		Map<String, String> wanted = new LinkedHashMap<>();
		if (next.enabled()) {
			next.webhooks().forEach((name, url) -> {
				if (!url.isEmpty()) {
					wanted.put(name, url);
				}
			});
			if (!next.chatWebhook().isEmpty()) {
				wanted.put(CHAT, next.chatWebhook());
			}
		}
		boolean deliveryChanged = config == null || config.maxAttempts() != next.maxAttempts() || config.queueSize() != next.queueSize();
		for (String name : List.copyOf(webhooks.keySet())) {
			if (deliveryChanged || !Objects.equals(urls.get(name), wanted.get(name))) {
				retire(webhooks.remove(name));
				urls.remove(name);
			}
		}
		wanted.forEach((name, url) -> {
			if (!webhooks.containsKey(name)) {
				try {
					webhooks.put(name, new WebhookClient(name, URI.create(url), http, redactor, log, listener(), next.queueSize(),
							next.maxAttempts(), 1000));
					urls.put(name, url);
				} catch (IllegalArgumentException e) {
					log.warn("[MejtaDiscord] Webhook '{}' has an invalid URL; it is not used", name);
				}
			}
		});

		boolean gatewayChanged = config == null || config.gatewayWanted() != next.gatewayWanted() || !config.token().equals(next.token());
		channelId = next.channelId();
		config = next;
		if (gatewayChanged) {
			if (gateway != null) {
				gateway.close();
				gateway = null;
			}
			if (next.gatewayWanted()) {
				startGateway(next.token());
			}
		}
	}

	private void startGateway(String token) {
		gateway = new GatewayClient(gatewayUrl, token, GatewayClient.CHAT_INTENTS, http, redactor, log, new GatewayClient.Handler() {
			@Override
			public void onMessage(JsonObject message) {
				GatewayClient g = gateway;
				Inbound.from(message, channelId, g == null ? "" : g.botUserId()).ifPresent(events::inbound);
			}

			@Override
			public void onState(GatewayClient.State state, String detail) {
				events.gatewayState(state, detail);
			}
		}, backoffMs);
		gateway.start();
	}

	/** Restarts the gateway connection, for example after fixing the Message Content intent. */
	public synchronized boolean reconnectGateway() {
		if (closed || config == null || !config.gatewayWanted()) {
			return false;
		}
		if (gateway != null) {
			gateway.close();
		}
		startGateway(config.token());
		return true;
	}

	private WebhookClient.Listener listener() {
		return new WebhookClient.Listener() {
			@Override
			public void disabled(String webhook, int httpStatus) {
				events.webhookDisabled(webhook, httpStatus);
			}

			@Override
			public void dropped(String webhook, long total) {
				events.dropped(webhook, total);
			}
		};
	}

	/** Old clients finish what they have queued in the background, so applying settings never blocks. */
	private void retire(WebhookClient client) {
		if (client == null) {
			return;
		}
		Thread t = new Thread(() -> client.close(Duration.ofSeconds(3)), "MejtaDiscord-Retire-" + client.name());
		t.setDaemon(true);
		t.start();
	}

	/** Queues a payload for the named webhook. {@code false} if it is not configured, disabled or full. */
	public boolean send(String webhook, JsonObject payload, boolean important) {
		WebhookClient client;
		synchronized (this) {
			client = webhooks.get(webhook);
		}
		return client != null && client.offer(new WebhookClient.Message(payload, important));
	}

	public synchronized boolean hasWebhook(String name) {
		return webhooks.containsKey(name);
	}

	public synchronized Optional<GatewayClient.State> gatewayState() {
		return Optional.ofNullable(gateway).map(GatewayClient::state);
	}

	/** Human-readable status for {@code /mejtaadmin mejtadiscord status}. Contains no secrets. */
	public synchronized List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		if (config == null) {
			lines.add("Not started (the server is not running).");
			return lines;
		}
		if (!config.enabled()) {
			lines.add("Disabled (setting 'enabled' is off).");
			return lines;
		}
		List<String> names = new ArrayList<>(config.webhooks().keySet());
		names.add(CHAT);
		for (String name : names) {
			WebhookClient c = webhooks.get(name);
			if (c == null) {
				lines.add("Webhook '" + name + "': not set");
				continue;
			}
			String line = "Webhook '" + name + "': " + c.status().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ", " + c.sentCount()
					+ " sent, " + c.droppedCount() + " dropped, " + c.pending() + " queued";
			lines.add(line);
			if (!c.lastError().isEmpty()) {
				lines.add("  last error: " + redactor.scrub(c.lastError()));
			}
		}
		if (!config.chatToMinecraft()) {
			lines.add("Discord to Minecraft: off");
		} else if (config.token().isEmpty() || config.channelId().isEmpty()) {
			lines.add("Discord to Minecraft: needs " + (config.token().isEmpty() ? "bot.token" : "")
					+ (config.token().isEmpty() && config.channelId().isEmpty() ? " and " : "")
					+ (config.channelId().isEmpty() ? "chat.to_minecraft.channel_id" : ""));
		} else if (gateway != null) {
			String error = gateway.lastError();
			lines.add("Discord to Minecraft: " + gateway.state().name().toLowerCase(Locale.ROOT)
					+ (error.isEmpty() ? "" : " (" + redactor.scrub(error) + ")"));
		}
		return lines;
	}

	@Override
	public void close() {
		close(Duration.ofSeconds(3));
	}

	/** Stops the gateway and gives queued webhook messages up to {@code drain} to go out. */
	public void close(Duration drain) {
		List<WebhookClient> clients;
		GatewayClient g;
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
			clients = new ArrayList<>(webhooks.values());
			webhooks.clear();
			g = gateway;
			gateway = null;
		}
		if (g != null) {
			g.close();
		}
		long end = System.nanoTime() + drain.toNanos();
		for (WebhookClient c : clients) {
			c.close(Duration.ofNanos(Math.max(0, end - System.nanoTime())));
		}
	}
}
