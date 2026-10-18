package com.mejta.mejtadiscord.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.mejta.mejtadiscord.text.Redactor;

/**
 * Sends messages to one Discord webhook, in order, from its own thread, so nothing waits on Discord.
 *
 * <ul>
 * <li>Rate limits: Discord's {@code X-RateLimit-Remaining}/{@code X-RateLimit-Reset-After} headers pause
 * sending before a limit is hit; a {@code 429} answer pauses for its {@code retry_after} and retries.</li>
 * <li>Server errors and network failures are retried with exponential backoff, up to the configured
 * number of attempts; then the message is dropped (it stays in MejtaCore's notification file log).</li>
 * <li>{@code 401}, {@code 403} or {@code 404} mean the webhook was deleted or is wrong: it is disabled
 * and the owner is told once.</li>
 * <li>Every request disables mentions ({@code allowed_mentions: {parse: []}}).</li>
 * <li>The queue is bounded. When it is full, routine messages are dropped first and counted.</li>
 * </ul>
 *
 * The webhook URL is a secret: it is never logged, and failures are logged through the {@link Redactor}.
 */
public final class WebhookClient implements AutoCloseable {
	public enum Status {
		IDLE,
		OK,
		RATE_LIMITED,
		RETRYING,
		FAILING,
		DISABLED
	}

	/** @param important kept in preference to routine messages when the queue overflows */
	public record Message(JsonObject payload, boolean important) {
	}

	/** Told about events the owner should know about; called on the webhook's thread. */
	public interface Listener {
		void disabled(String webhook, int httpStatus);

		default void dropped(String webhook, long total) {
		}
	}

	private static final String USER_AGENT = "DiscordBot (https://github.com/Slowing4356/mejta, 0.1.0) MejtaDiscord";

	private final String name;
	private final URI url;
	private final HttpClient http;
	private final Redactor redactor;
	private final Logger log;
	private final Listener listener;
	private final int maxAttempts;
	private final long baseBackoffMs;
	private final BlockingQueue<Message> queue;
	private final Thread worker;
	private final AtomicLong sent = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	private volatile Status status = Status.IDLE;
	private volatile String lastError = "";
	private volatile boolean closing;
	private volatile long pausedUntil;

	/**
	 * @param baseBackoffMs first retry delay; doubled per attempt, at most 30 s (tests use a few ms)
	 */
	public WebhookClient(String name, URI url, HttpClient http, Redactor redactor, Logger log, Listener listener, int queueSize,
			int maxAttempts, long baseBackoffMs) {
		this.name = name;
		this.url = url;
		this.http = http;
		this.redactor = redactor;
		this.log = log;
		this.listener = listener;
		this.maxAttempts = Math.max(1, maxAttempts);
		this.baseBackoffMs = Math.max(1, baseBackoffMs);
		this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
		this.worker = new Thread(this::run, "MejtaDiscord-Webhook-" + name);
		this.worker.setDaemon(true);
		this.worker.start();
	}

	public String name() {
		return name;
	}

	public Status status() {
		return status;
	}

	public long sentCount() {
		return sent.get();
	}

	public long droppedCount() {
		return dropped.get();
	}

	/** The last failure, already redacted. */
	public String lastError() {
		return lastError;
	}

	public int pending() {
		return queue.size();
	}

	/** Queues a message; never blocks. Returns {@code false} if it was dropped. */
	public boolean offer(Message message) {
		if (closing || status == Status.DISABLED) {
			return false;
		}
		if (queue.offer(message)) {
			return true;
		}
		if (message.important()) {
			synchronized (queue) {
				for (Iterator<Message> it = queue.iterator(); it.hasNext();) {
					if (!it.next().important()) {
						it.remove();
						countDrop();
						break;
					}
				}
			}
			if (queue.offer(message)) {
				return true;
			}
		}
		countDrop();
		return false;
	}

	private void countDrop() {
		long total = dropped.incrementAndGet();
		if (total == 1 || total % 100 == 0) {
			listener.dropped(name, total);
		}
	}

	private void run() {
		while (true) {
			Message message;
			try {
				message = queue.poll(200, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				return;
			}
			if (message == null) {
				if (closing) {
					return;
				}
				continue;
			}
			if (status == Status.DISABLED) {
				countDrop();
				continue;
			}
			try {
				deliver(message);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void deliver(Message message) throws InterruptedException {
		JsonObject payload = message.payload().deepCopy();
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		payload.add("allowed_mentions", mentions);
		byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
		int attempts = 0;
		int rateLimitWaits = 0;
		while (true) {
			waitWhilePaused();
			HttpResponse<String> response;
			try {
				HttpRequest request = HttpRequest.newBuilder(url)
						.timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json")
						.header("User-Agent", USER_AGENT)
						.POST(HttpRequest.BodyPublishers.ofByteArray(body))
						.build();
				response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException e) {
				attempts++;
				if (!retryLater(attempts, redactor.describe(e))) {
					return;
				}
				continue;
			}
			int code = response.statusCode();
			if (code >= 200 && code < 300) {
				sent.incrementAndGet();
				status = Status.OK;
				applyBucketHeaders(response);
				return;
			}
			if (code == 429) {
				double retryAfter = retryAfterSeconds(response);
				pausedUntil = System.currentTimeMillis() + (long) Math.ceil(retryAfter * 1000);
				status = Status.RATE_LIMITED;
				if (++rateLimitWaits > 10) {
					lastError = "rate limited 10 times in a row; message dropped";
					countDrop();
					return;
				}
				continue;
			}
			if (code == 401 || code == 403 || code == 404) {
				status = Status.DISABLED;
				lastError = "Discord answered " + code + ": the webhook was deleted or the URL is wrong";
				log.warn("[MejtaDiscord] Webhook '{}' disabled: {}", name, lastError);
				queue.clear();
				listener.disabled(name, code);
				return;
			}
			if (code >= 500) {
				attempts++;
				if (!retryLater(attempts, "Discord answered " + code)) {
					return;
				}
				continue;
			}
			// Other 4xx: the message itself is invalid; retrying would not help.
			lastError = "Discord rejected a message with " + code + ": " + redactor.scrub(Sanitized.shorten(response.body()));
			log.warn("[MejtaDiscord] Webhook '{}': {}", name, lastError);
			countDrop();
			return;
		}
	}

	/** @return whether to try again (after sleeping); {@code false} means the message was dropped */
	private boolean retryLater(int attempts, String error) throws InterruptedException {
		lastError = error;
		if (attempts >= maxAttempts) {
			countDrop(); // counted before the status changes, so anyone seeing FAILING also sees the drop
			status = Status.FAILING;
			log.warn("[MejtaDiscord] Webhook '{}': giving up on a message after {} attempt(s): {}", name, attempts, error);
			return false;
		}
		status = Status.RETRYING;
		long delay = Math.min(30_000, baseBackoffMs << Math.min(attempts - 1, 20));
		if (closing) {
			delay = Math.min(delay, 200);
		}
		Thread.sleep(delay);
		return true;
	}

	private void waitWhilePaused() throws InterruptedException {
		long wait = pausedUntil - System.currentTimeMillis();
		if (wait > 0) {
			Thread.sleep(Math.min(wait, 60_000));
		}
	}

	private void applyBucketHeaders(HttpResponse<String> response) {
		String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
		String resetAfter = response.headers().firstValue("X-RateLimit-Reset-After").orElse(null);
		if ("0".equals(remaining) && resetAfter != null) {
			try {
				pausedUntil = System.currentTimeMillis() + (long) Math.ceil(Double.parseDouble(resetAfter) * 1000);
			} catch (NumberFormatException ignored) {
				// a malformed header only means no early pause
			}
		}
	}

	static double retryAfterSeconds(HttpResponse<String> response) {
		try {
			JsonElement body = JsonParser.parseString(response.body());
			if (body.isJsonObject() && body.getAsJsonObject().has("retry_after")) {
				return clampRetry(body.getAsJsonObject().get("retry_after").getAsDouble());
			}
		} catch (RuntimeException ignored) {
			// fall back to the header
		}
		try {
			return clampRetry(Double.parseDouble(response.headers().firstValue("Retry-After").orElse("1")));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static double clampRetry(double seconds) {
		return Double.isFinite(seconds) ? Math.clamp(seconds, 0.0, 60.0) : 1.0;
	}

	/** Sends what is queued for up to {@code drain}, then stops the thread. */
	@Override
	public void close() {
		close(Duration.ofSeconds(5));
	}

	public void close(Duration drain) {
		closing = true;
		try {
			worker.join(Math.max(1, drain.toMillis())); // join(0) would wait forever
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		worker.interrupt();
	}

	/** Text helpers shared with the gateway client. */
	static final class Sanitized {
		private Sanitized() {
		}

		static String shorten(String text) {
			if (text == null) {
				return "";
			}
			return text.length() <= 200 ? text : text.substring(0, 200) + "...";
		}
	}
}
