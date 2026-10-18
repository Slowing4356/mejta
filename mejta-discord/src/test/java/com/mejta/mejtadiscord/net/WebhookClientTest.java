package com.mejta.mejtadiscord.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import com.mejta.mejtadiscord.text.Redactor;

class WebhookClientTest {
	private static final Logger LOG = LoggerFactory.getLogger("WebhookClientTest");

	/** A planned answer: status, extra headers, body. */
	record Answer(int status, String body, String... headers) {
	}

	HttpServer server;
	final Deque<Answer> plan = new ArrayDeque<>();
	final List<String> received = new CopyOnWriteArrayList<>();
	final List<Long> times = new CopyOnWriteArrayList<>();
	final List<String> events = new CopyOnWriteArrayList<>();
	URI url;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/webhooks/1/secretpart", exchange -> {
			received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			times.add(System.currentTimeMillis());
			Answer answer;
			synchronized (plan) {
				answer = plan.isEmpty() ? new Answer(204, "") : plan.poll();
			}
			for (int i = 0; i + 1 < answer.headers().length; i += 2) {
				exchange.getResponseHeaders().add(answer.headers()[i], answer.headers()[i + 1]);
			}
			byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			}
			exchange.close();
		});
		server.start();
		url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/secretpart");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	WebhookClient client(int queue, int attempts) {
		return new WebhookClient("test", url, HttpClient.newHttpClient(), new Redactor(), LOG, new WebhookClient.Listener() {
			@Override
			public void disabled(String webhook, int status) {
				events.add("disabled " + status);
			}

			@Override
			public void dropped(String webhook, long total) {
				events.add("dropped " + total);
			}
		}, queue, attempts, 5);
	}

	static WebhookClient.Message msg(String text, boolean important) {
		JsonObject o = new JsonObject();
		o.addProperty("content", text);
		return new WebhookClient.Message(o, important);
	}

	static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 300; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(10);
		}
		return condition.getAsBoolean();
	}

	@Test
	void deliversInOrderWithMentionsDisabled() throws Exception {
		try (WebhookClient c = client(10, 3)) {
			for (int i = 0; i < 5; i++) {
				assertTrue(c.offer(msg("m" + i, false)));
			}
			assertTrue(waitFor(() -> received.size() == 5));
			for (int i = 0; i < 5; i++) {
				JsonObject body = JsonParser.parseString(received.get(i)).getAsJsonObject();
				assertEquals("m" + i, body.get("content").getAsString());
				assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "mentions disabled");
			}
			assertEquals(WebhookClient.Status.OK, c.status());
		}
	}

	@Test
	void waitsOutA429AndRetries() throws Exception {
		plan.add(new Answer(429, "{\"retry_after\": 0.3, \"global\": false}"));
		try (WebhookClient c = client(10, 3)) {
			c.offer(msg("limited", false));
			assertTrue(waitFor(() -> received.size() == 2));
			assertTrue(times.get(1) - times.get(0) >= 280, "waited about retry_after before retrying: " + (times.get(1) - times.get(0)));
			assertTrue(waitFor(() -> c.sentCount() == 1), "the retry was delivered");
		}
	}

	@Test
	void pausesWhenTheBucketIsEmpty() throws Exception {
		plan.add(new Answer(204, "", "X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "0.3"));
		try (WebhookClient c = client(10, 3)) {
			c.offer(msg("a", false));
			c.offer(msg("b", false));
			assertTrue(waitFor(() -> received.size() == 2));
			assertTrue(times.get(1) - times.get(0) >= 280, "second request waited for the bucket reset");
		}
	}

	@Test
	void retriesServerErrorsThenGivesUp() throws Exception {
		plan.add(new Answer(502, ""));
		try (WebhookClient c = client(10, 3)) {
			c.offer(msg("eventually", false));
			assertTrue(waitFor(() -> c.sentCount() == 1), "succeeds on the second attempt");
		}
		received.clear();
		for (int i = 0; i < 3; i++) {
			plan.add(new Answer(500, ""));
		}
		try (WebhookClient c = client(10, 3)) {
			c.offer(msg("never", false));
			assertTrue(waitFor(() -> c.status() == WebhookClient.Status.FAILING));
			assertEquals(3, received.size(), "exactly the configured number of attempts");
			assertEquals(1, c.droppedCount());
		}
	}

	@Test
	void deletedWebhookIsDisabled() throws Exception {
		plan.add(new Answer(404, "{\"message\": \"Unknown Webhook\", \"code\": 10015}"));
		try (WebhookClient c = client(10, 3)) {
			c.offer(msg("x", false));
			assertTrue(waitFor(() -> c.status() == WebhookClient.Status.DISABLED));
			assertTrue(events.contains("disabled 404"));
			assertFalse(c.offer(msg("y", false)), "nothing more is accepted");
			Thread.sleep(100);
			assertEquals(1, received.size());
		}
	}

	@Test
	void fullQueueDropsRoutineMessagesFirst() throws Exception {
		plan.add(new Answer(429, "{\"retry_after\": 0.5}")); // keeps the worker busy while the queue fills
		try (WebhookClient c = client(2, 3)) {
			c.offer(msg("first", false));
			assertTrue(waitFor(() -> received.size() == 1));
			assertTrue(c.offer(msg("r1", false)));
			assertTrue(c.offer(msg("r2", false)));
			assertFalse(c.offer(msg("r3", false)), "routine message dropped when full");
			assertTrue(c.offer(msg("kick", true)), "important message replaces a routine one");
			assertTrue(waitFor(() -> received.stream().anyMatch(b -> b.contains("kick"))));
			assertEquals(2, c.droppedCount());
			assertTrue(events.contains("dropped 1"));
		}
	}

	@Test
	void urlNeverAppearsInErrors() throws Exception {
		server.stop(0); // connection refused
		try (WebhookClient c = client(10, 2)) {
			c.offer(msg("x", false));
			assertTrue(waitFor(() -> c.status() == WebhookClient.Status.FAILING));
			assertFalse(c.lastError().contains("secretpart"), c.lastError());
		}
	}

	@Test
	void aFailingMessageDoesNotStopTheWorker() throws Exception {
		try (WebhookClient c = client(10, 3)) {
			assertTrue(c.offer(new WebhookClient.Message(null, false))); // makes delivery throw
			assertTrue(c.offer(msg("after", false)));
			assertTrue(waitFor(() -> c.sentCount() == 1), "the next message still goes out");
			assertEquals(1, c.droppedCount());
			assertTrue(c.lastError().startsWith("internal error"), c.lastError());
		}
	}
}
