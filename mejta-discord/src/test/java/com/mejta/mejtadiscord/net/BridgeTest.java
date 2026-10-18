package com.mejta.mejtadiscord.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import com.mejta.mejtadiscord.text.Inbound;
import com.mejta.mejtadiscord.text.Redactor;

class BridgeTest {
	private static final Logger LOG = LoggerFactory.getLogger("BridgeTest");
	private static final String TOKEN = "MTIz.fake.bot-token-value";
	private static final String CHANNEL = "111111111111";

	HttpServer server;
	FakeGateway gateway;
	Bridge bridge;
	Redactor redactor;
	String alertsUrl;
	String chatUrl;
	String slowUrl;
	String goneUrl;
	/** Holds the slow webhook's answers until released, so messages pile up in the queue. */
	final CountDownLatch gate = new CountDownLatch(1);
	final CountDownLatch slowArrived = new CountDownLatch(1);
	final AtomicInteger goneAnswers = new AtomicInteger();
	final List<String> slow = new CopyOnWriteArrayList<>();
	final List<String> alerts = new CopyOnWriteArrayList<>();
	final List<String> chat = new CopyOnWriteArrayList<>();
	final List<Inbound> inbound = new CopyOnWriteArrayList<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/webhooks/1/alerts-secret", exchange -> {
			alerts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.createContext("/api/webhooks/2/chat-secret", exchange -> {
			chat.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.createContext("/api/webhooks/3/slow-secret", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			slowArrived.countDown();
			try {
				gate.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			slow.add(body);
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.createContext("/api/webhooks/4/gone-secret", exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(goneAnswers.getAndIncrement() == 0 ? 404 : 204, -1); // restored after the first answer
			exchange.close();
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		alertsUrl = base + "/api/webhooks/1/alerts-secret";
		chatUrl = base + "/api/webhooks/2/chat-secret";
		slowUrl = base + "/api/webhooks/3/slow-secret";
		goneUrl = base + "/api/webhooks/4/gone-secret";
		gateway = new FakeGateway();
		redactor = new Redactor();
		bridge = new Bridge(HttpClient.newHttpClient(), redactor, LOG, inbound::add, gateway.uri(), 10);
	}

	@AfterEach
	void stop() throws Exception {
		gate.countDown();
		bridge.close();
		gateway.close();
		server.stop(0);
	}

	Bridge.Config config(boolean enabled, String alerts, boolean toMinecraft, String token) {
		return new Bridge.Config(enabled, Map.of("default", alerts, "staff", ""), chatUrl, true, toMinecraft, token, CHANNEL, 3, 50);
	}

	Bridge.Config config(String alerts, int maxAttempts, int queueSize) {
		return new Bridge.Config(true, Map.of("default", alerts, "staff", ""), chatUrl, true, false, "", CHANNEL, maxAttempts, queueSize);
	}

	static JsonObject content(String text) {
		JsonObject o = new JsonObject();
		o.addProperty("content", text);
		return o;
	}

	@Test
	void sendsToConfiguredWebhooksOnly() throws Exception {
		bridge.apply(config(true, alertsUrl, false, ""));
		assertTrue(bridge.send("default", content("alert"), true));
		assertTrue(bridge.send(Bridge.CHAT, content("chat"), false));
		assertFalse(bridge.send("staff", content("x"), true), "not configured");
		assertFalse(bridge.send("unknown", content("x"), true));
		assertTrue(WebhookClientTest.waitFor(() -> alerts.size() == 1 && chat.size() == 1));
		assertTrue(alerts.get(0).contains("alert"));
	}

	@Test
	void disabledSendsNothing() throws Exception {
		bridge.apply(config(false, alertsUrl, true, TOKEN));
		assertFalse(bridge.send("default", content("alert"), true));
		assertTrue(gateway.next(200) == null, "no gateway while disabled");
		assertTrue(bridge.statusLines().get(0).startsWith("Disabled"));
	}

	@Test
	void statusNeverShowsSecrets() {
		bridge.apply(config(true, alertsUrl, true, ""));
		String status = String.join("\n", bridge.statusLines());
		assertFalse(status.contains("secret"), status);
		assertTrue(status.contains("Webhook 'default': idle"), status);
		assertTrue(status.contains("Webhook 'staff': not set"), status);
		assertTrue(status.contains("needs bot.token"), status);
	}

	@Test
	void unchangedWebhooksSurviveASettingsChange() throws Exception {
		bridge.apply(config(true, alertsUrl, false, ""));
		assertTrue(bridge.send("default", content("one"), true));
		assertTrue(WebhookClientTest.waitFor(() -> defaultLine().contains("1 sent"))); // counted once the answer is in, after the server saw it
		bridge.apply(config(true, alertsUrl, false, "")); // e.g. only a format setting changed
		assertTrue(String.join("\n", bridge.statusLines()).contains("1 sent"), "same client, counters kept");
		bridge.apply(config(true, "", false, ""));
		assertFalse(bridge.send("default", content("two"), true), "removed");
	}

	@Test
	void relaysChannelMessagesFromTheGateway() throws Exception {
		bridge.apply(config(true, alertsUrl, true, TOKEN));
		FakeGateway.Conn conn = gateway.next(3000);
		assertNotNull(conn, "gateway started");
		conn.hello(45_000);
		JsonObject identify = conn.expect(2, 3000);
		assertEquals(TOKEN, identify.getAsJsonObject("d").get("token").getAsString());
		JsonObject ready = new JsonObject();
		ready.addProperty("session_id", "s");
		JsonObject user = new JsonObject();
		user.addProperty("id", "999999999999");
		ready.add("user", user);
		conn.dispatch("READY", 1, ready);
		assertTrue(WebhookClientTest.waitFor(() -> bridge.gatewayState().orElse(null) == GatewayClient.State.READY));

		conn.dispatch("MESSAGE_CREATE", 2, TextMessages.message(CHANNEL, "777777777777", "alex", false, "hello"));
		conn.dispatch("MESSAGE_CREATE", 3, TextMessages.message(CHANNEL, "999999999999", "us", false, "own bot"));
		conn.dispatch("MESSAGE_CREATE", 4, TextMessages.message("222222222222", "777777777777", "alex", false, "elsewhere"));
		conn.dispatch("MESSAGE_CREATE", 5, TextMessages.message(CHANNEL, "888888888888", "bot", true, "a bot"));
		conn.dispatch("MESSAGE_CREATE", 6, TextMessages.message(CHANNEL, "777777777777", "alex", false, "second"));
		assertTrue(WebhookClientTest.waitFor(() -> inbound.size() == 2));
		assertEquals("hello", inbound.get(0).content());
		assertEquals("second", inbound.get(1).content());

		bridge.apply(config(true, alertsUrl, false, TOKEN));
		assertTrue(WebhookClientTest.waitFor(() -> conn.closeCode == 1000), "gateway closed when the relay is turned off");
		assertTrue(bridge.gatewayState().isEmpty());
	}

	/** Builds MESSAGE_CREATE data. */
	static final class TextMessages {
		static JsonObject message(String channel, String userId, String username, boolean bot, String content) {
			JsonObject author = new JsonObject();
			author.addProperty("id", userId);
			author.addProperty("username", username);
			if (bot) {
				author.addProperty("bot", true);
			}
			JsonObject d = new JsonObject();
			d.addProperty("channel_id", channel);
			d.addProperty("type", 0);
			d.addProperty("content", content);
			d.add("author", author);
			return d;
		}
	}

	@Test
	void closeIsQuickAndFinal() {
		bridge.apply(config(true, alertsUrl, false, ""));
		long start = System.nanoTime();
		bridge.close(java.time.Duration.ZERO);
		assertTrue(System.nanoTime() - start < 2_000_000_000L, "close(0) does not hang");
		bridge.apply(config(true, alertsUrl, false, ""));
		assertFalse(bridge.send("default", content("late"), true));
	}

	/** The status line of the 'default' webhook. */
	String defaultLine() {
		return bridge.statusLines().stream().filter(l -> l.startsWith("Webhook 'default'")).findFirst().orElse("");
	}

	/** One message stuck at the slow webhook, then {@code queued} more waiting behind it. */
	void blockWithQueued(int queued) throws Exception {
		assertTrue(bridge.send("default", content("m0"), false));
		assertTrue(slowArrived.await(3, TimeUnit.SECONDS), "m0 in flight");
		for (int i = 1; i <= queued; i++) {
			assertTrue(bridge.send("default", content("m" + i), false));
		}
		assertTrue(defaultLine().contains(queued + " queued"), defaultLine());
	}

	@Test
	void deliverySettingsChangeKeepsQueuedMessages() throws Exception {
		bridge.apply(config(slowUrl, 3, 50));
		blockWithQueued(3);
		bridge.apply(config(slowUrl, 5, 50)); // only delivery.max_attempts changed
		gate.countDown();
		assertTrue(WebhookClientTest.waitFor(() -> slow.size() == 4), "nothing lost: " + slow);
		// m0 was finished by the old client; the queued three went out through the new one.
		assertTrue(WebhookClientTest.waitFor(() -> defaultLine().contains("3 sent, 0 dropped")), defaultLine());
	}

	@Test
	void smallerQueueCountsWhatDoesNotFit() throws Exception {
		bridge.apply(config(slowUrl, 3, 50));
		blockWithQueued(6);
		bridge.apply(config(slowUrl, 3, 2)); // delivery.queue_size shrank below what is queued
		gate.countDown();
		// The new client may already be sending one while the rest move over, so at least 3 of the 6 do not fit.
		assertTrue(WebhookClientTest.waitFor(() -> dropped() >= 3 && slow.size() + dropped() == 7), "every message sent or counted: "
				+ slow.size() + " sent, " + defaultLine());
	}

	long dropped() {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) dropped").matcher(defaultLine());
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	@Test
	void reconnectReenablesARefusedWebhook() throws Exception {
		bridge.apply(config(goneUrl, 3, 50));
		assertTrue(bridge.send("default", content("first"), true));
		assertTrue(WebhookClientTest.waitFor(() -> defaultLine().contains("disabled")), defaultLine());
		assertFalse(bridge.send("default", content("while disabled"), true));
		assertEquals(List.of("default"), bridge.reenableWebhooks());
		assertEquals(List.of(), bridge.reenableWebhooks(), "only disabled ones are recreated");
		assertTrue(bridge.send("default", content("again"), true));
		assertTrue(WebhookClientTest.waitFor(() -> defaultLine().contains("ok, 1 sent")), defaultLine());
	}

	@Test
	void chatWebhookIsASecretEvenWhenUnused() {
		bridge.apply(new Bridge.Config(true, Map.of("default", alertsUrl), chatUrl, false, false, TOKEN, CHANNEL, 3, 50));
		assertFalse(bridge.hasWebhook(Bridge.CHAT), "chat.to_discord is off");
		assertEquals("<secret>", redactor.scrub(chatUrl));
		assertEquals("<secret>", redactor.scrub(alertsUrl));
	}

	@Test
	void configToStringHidesSecrets() {
		String text = new Bridge.Config(true, Map.of("default", alertsUrl, "staff", ""), chatUrl, true, true, TOKEN, CHANNEL, 3, 50).toString();
		assertFalse(text.contains("secret") || text.contains(TOKEN) || text.contains("127.0.0.1"), text);
		assertTrue(text.contains("default=set") && text.contains("staff=not set") && text.contains("token=set"), text);
	}

	@Test
	void turningTheRelayOffDoesNotWaitForAPendingReconnect() throws Exception {
		Bridge slowRetry = new Bridge(HttpClient.newHttpClient(), new Redactor(), LOG, inbound::add, gateway.uri(), 10_000);
		try {
			slowRetry.apply(config(true, alertsUrl, true, TOKEN));
			FakeGateway.Conn conn = gateway.next(3000);
			assertNotNull(conn);
			conn.close(4000); // the client now waits 10 s or more to reconnect
			assertTrue(WebhookClientTest.waitFor(() -> slowRetry.gatewayState().orElse(null) == GatewayClient.State.BACKOFF));
			long start = System.nanoTime();
			slowRetry.apply(config(true, alertsUrl, false, TOKEN));
			assertTrue(System.nanoTime() - start < 1_000_000_000L, "returned after " + (System.nanoTime() - start) / 1_000_000 + " ms");
			assertTrue(slowRetry.gatewayState().isEmpty());
		} finally {
			slowRetry.close(java.time.Duration.ZERO);
		}
	}
}
