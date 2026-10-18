package com.mejta.mejtadiscord.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mejta.mejtadiscord.text.Redactor;

/**
 * A minimal Discord Gateway (v10, JSON) client on Java's built-in WebSocket: enough to receive
 * messages for the Discord-to-Minecraft chat relay. It identifies with the bot token and intents,
 * keeps the heartbeat, detects dead ("zombie") connections, resumes after disconnects and reconnects
 * with exponential backoff. Fatal close codes (invalid token, intents not allowed) stop it with a
 * clear reason instead of retrying forever.
 *
 * <p>All protocol state is changed on one internal thread. The token is never logged.
 */
public final class GatewayClient implements AutoCloseable {
	public enum State {
		STOPPED,
		CONNECTING,
		IDENTIFYING,
		RESUMING,
		READY,
		BACKOFF,
		/** Stopped for good: see {@link #lastError()}. */
		FAILED
	}

	public interface Handler {
		/** A {@code MESSAGE_CREATE} dispatch's data. Called on the gateway thread; hand work over quickly. */
		void onMessage(JsonObject message);

		default void onState(State state, String detail) {
		}
	}

	/** GUILDS | GUILD_MESSAGES | MESSAGE_CONTENT (privileged: enable it in the Developer Portal). */
	public static final int CHAT_INTENTS = (1) | (1 << 9) | (1 << 15);

	private static final Set<Integer> FATAL = Set.of(4004, 4010, 4011, 4012, 4013, 4014);

	private final URI gatewayUrl;
	private final String token;
	private final int intents;
	private final HttpClient http;
	private final Redactor redactor;
	private final Logger log;
	private final Handler handler;
	private final long baseBackoffMs;
	private final ScheduledExecutorService exec;

	// Only touched on exec.
	private WebSocket socket;
	private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
	private ScheduledFuture<?> heartbeat;
	private boolean acked = true;
	private Integer sequence;
	private String sessionId;
	private URI resumeUrl;
	private long backoffMs;
	private int connectionId;
	private boolean closing;

	private volatile State state = State.STOPPED;
	private volatile String lastError = "";
	private volatile String botUserId = "";

	public GatewayClient(URI gatewayUrl, String token, int intents, HttpClient http, Redactor redactor, Logger log, Handler handler,
			long baseBackoffMs) {
		this.gatewayUrl = gatewayUrl;
		this.token = token;
		this.intents = intents;
		this.http = http;
		this.redactor = redactor;
		this.log = log;
		this.handler = handler;
		this.baseBackoffMs = Math.max(1, baseBackoffMs);
		this.backoffMs = this.baseBackoffMs;
		this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "MejtaDiscord-Gateway");
			t.setDaemon(true);
			return t;
		});
	}

	public State state() {
		return state;
	}

	/** Redacted. */
	public String lastError() {
		return lastError;
	}

	/** The bot's own user ID, once ready: messages from it are ignored. */
	public String botUserId() {
		return botUserId;
	}

	public void start() {
		if (!exec.isShutdown()) {
			exec.execute(() -> connect(gatewayUrl));
		}
	}

	private void setState(State next, String detail) {
		state = next;
		try {
			handler.onState(next, detail);
		} catch (RuntimeException e) {
			log.warn("[MejtaDiscord] Gateway state listener failed: {}", redactor.describe(e));
		}
	}

	// ---------------------------------------------------------------- connection

	private void connect(URI base) {
		if (closing) {
			return;
		}
		int id = ++connectionId;
		setState(sessionId != null ? State.RESUMING : State.CONNECTING, "");
		URI uri = withQuery(base);
		http.newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(uri, new Listener(id))
				.whenComplete((ws, error) -> exec.execute(() -> {
					if (error != null) {
						if (id == connectionId) {
							lastError = redactor.describe(error);
							scheduleReconnect();
						}
					} else if (id != connectionId || closing) {
						ws.abort();
					}
				}));
	}

	private static URI withQuery(URI base) {
		String text = base.toString();
		return text.contains("?") ? base : URI.create(text + (text.endsWith("/") ? "" : "/") + "?v=10&encoding=json");
	}

	private void scheduleReconnect() {
		stopHeartbeat();
		if (closing || state == State.FAILED) {
			return;
		}
		long delay = backoffMs + ThreadLocalRandom.current().nextLong(Math.max(1, backoffMs / 2));
		backoffMs = Math.min(60_000, backoffMs * 2);
		setState(State.BACKOFF, lastError);
		URI target = sessionId != null && resumeUrl != null ? resumeUrl : gatewayUrl;
		exec.schedule(() -> connect(target), delay, TimeUnit.MILLISECONDS);
	}

	private void dropConnection(int code, String reason) {
		connectionId++; // late callbacks of the old socket are ignored
		WebSocket old = socket;
		socket = null;
		sending = CompletableFuture.completedFuture(null);
		if (old != null) {
			try {
				old.sendClose(code, reason).orTimeout(2, TimeUnit.SECONDS).whenComplete((w, e) -> old.abort());
			} catch (RuntimeException e) {
				old.abort();
			}
		}
	}

	private final class Listener implements WebSocket.Listener {
		private final int id;
		private final StringBuilder text = new StringBuilder();

		Listener(int id) {
			this.id = id;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			// Set before any message is handled: both run on exec, in this order.
			exec.execute(() -> {
				if (id == connectionId && !closing) {
					socket = webSocket;
				} else {
					webSocket.abort();
				}
			});
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			text.append(data);
			if (last) {
				String payload = text.toString();
				text.setLength(0);
				exec.execute(() -> {
					if (id == connectionId) {
						handle(payload);
					}
				});
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			exec.execute(() -> {
				if (id == connectionId) {
					closed(statusCode, reason);
				}
			});
			webSocket.abort();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			exec.execute(() -> {
				if (id == connectionId) {
					lastError = redactor.describe(error);
					closed(-1, lastError);
				}
			});
		}
	}

	private void closed(int code, String reason) {
		socket = null;
		connectionId++;
		stopHeartbeat();
		if (closing) {
			setState(State.STOPPED, "");
			return;
		}
		if (FATAL.contains(code)) {
			lastError = describeFatal(code);
			log.error("[MejtaDiscord] Discord closed the gateway: {}. The bot will not reconnect until the settings are fixed.", lastError);
			setState(State.FAILED, lastError);
			return;
		}
		if (code == 4007 || code == 4009) {
			sessionId = null; // cannot resume these
		}
		lastError = "gateway closed (" + code + (reason == null || reason.isEmpty() ? "" : ": " + redactor.scrub(reason)) + ")";
		scheduleReconnect();
	}

	static String describeFatal(int code) {
		return switch (code) {
			case 4004 -> "the bot token is invalid (4004)";
			case 4013 -> "invalid intents (4013)";
			case 4014 -> "the Message Content intent is not enabled for this bot in the Discord Developer Portal (4014)";
			default -> "Discord refused the connection (" + code + ")";
		};
	}

	// ---------------------------------------------------------------- protocol

	private void handle(String raw) {
		try {
			handle(JsonParser.parseString(raw).getAsJsonObject());
		} catch (RuntimeException e) {
			// Never print the payload: it may hold message text. Exceptions on exec would be lost otherwise.
			log.warn("[MejtaDiscord] Ignoring a malformed gateway message: {}", redactor.describe(e));
		}
	}

	private void handle(JsonObject payload) {
		int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
		JsonElement d = payload.get("d");
		switch (op) {
			case 10 -> hello(d.getAsJsonObject().get("heartbeat_interval").getAsLong());
			case 11 -> acked = true;
			case 1 -> sendHeartbeat();
			case 0 -> dispatch(payload);
			case 7 -> {
				dropConnection(4000, "reconnect requested");
				scheduleReconnect();
			}
			case 9 -> {
				boolean resumable = d != null && d.isJsonPrimitive() && d.getAsBoolean();
				if (!resumable) {
					sessionId = null;
					sequence = null;
				}
				dropConnection(4000, "invalid session");
				scheduleReconnect();
			}
			default -> {
			}
		}
	}

	private void hello(long interval) {
		stopHeartbeat();
		acked = true;
		long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
		heartbeat = exec.scheduleAtFixedRate(this::heartbeatTick, first, interval, TimeUnit.MILLISECONDS);
		if (sessionId != null) {
			JsonObject d = new JsonObject();
			d.addProperty("token", token);
			d.addProperty("session_id", sessionId);
			if (sequence != null) {
				d.addProperty("seq", sequence);
			} else {
				d.add("seq", JsonNull.INSTANCE);
			}
			send(op(6, d));
			setState(State.RESUMING, "");
		} else {
			JsonObject properties = new JsonObject();
			properties.addProperty("os", System.getProperty("os.name", "unknown"));
			properties.addProperty("browser", "MejtaDiscord");
			properties.addProperty("device", "MejtaDiscord");
			JsonObject d = new JsonObject();
			d.addProperty("token", token);
			d.addProperty("intents", intents);
			d.add("properties", properties);
			send(op(2, d));
			setState(State.IDENTIFYING, "");
		}
	}

	private void heartbeatTick() {
		if (!acked) {
			// No acknowledgement since the last heartbeat: the connection is dead even if not closed.
			lastError = "no heartbeat acknowledgement (zombie connection)";
			dropConnection(4000, "zombie connection");
			scheduleReconnect();
			return;
		}
		acked = false;
		sendHeartbeat();
	}

	private void sendHeartbeat() {
		JsonObject payload = new JsonObject();
		payload.addProperty("op", 1);
		if (sequence != null) {
			payload.addProperty("d", sequence);
		} else {
			payload.add("d", JsonNull.INSTANCE);
		}
		send(payload);
	}

	private void dispatch(JsonObject payload) {
		if (payload.has("s") && !payload.get("s").isJsonNull()) {
			sequence = payload.get("s").getAsInt();
		}
		String type = payload.has("t") && !payload.get("t").isJsonNull() ? payload.get("t").getAsString() : "";
		JsonObject d = payload.has("d") && payload.get("d").isJsonObject() ? payload.getAsJsonObject("d") : new JsonObject();
		switch (type) {
			case "READY" -> {
				sessionId = d.get("session_id").getAsString();
				resumeUrl = d.has("resume_gateway_url") ? URI.create(d.get("resume_gateway_url").getAsString()) : null;
				botUserId = d.has("user") ? d.getAsJsonObject("user").get("id").getAsString() : "";
				backoffMs = baseBackoffMs;
				lastError = "";
				setState(State.READY, "");
			}
			case "RESUMED" -> {
				backoffMs = baseBackoffMs;
				lastError = "";
				setState(State.READY, "resumed");
			}
			case "MESSAGE_CREATE" -> {
				try {
					handler.onMessage(d);
				} catch (RuntimeException e) {
					log.warn("[MejtaDiscord] Message handler failed: {}", redactor.describe(e));
				}
			}
			default -> {
			}
		}
	}

	private static JsonObject op(int op, JsonObject d) {
		JsonObject payload = new JsonObject();
		payload.addProperty("op", op);
		payload.add("d", d);
		return payload;
	}

	/** Java's WebSocket allows one outstanding send; sends are chained. Called on exec only. */
	private void send(JsonObject payload) {
		WebSocket ws = socket;
		if (ws == null) {
			return;
		}
		String text = payload.toString();
		sending = sending.handle((r, e) -> null).thenCompose(v -> ws.sendText(text, true));
	}

	private void stopHeartbeat() {
		if (heartbeat != null) {
			heartbeat.cancel(false);
			heartbeat = null;
		}
	}

	@Override
	public void close() {
		if (exec.isShutdown()) {
			return;
		}
		exec.execute(() -> {
			closing = true;
			stopHeartbeat();
			dropConnection(1000, "shutting down");
			setState(State.STOPPED, "");
		});
		exec.shutdown();
		try {
			exec.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
