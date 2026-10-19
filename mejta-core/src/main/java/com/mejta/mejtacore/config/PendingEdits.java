package com.mejta.mejtacore.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Client-side bookkeeping for edits sent to the server's Server Settings and not answered yet.
 *
 * <p>The server answers every edit with a result and then a fresh catalog of values, in that order. A
 * catalog can also arrive while edits are still on their way (the reply to opening the page, or the
 * broadcast after someone's change), and it then still holds the old values. Showing those values made
 * the setting jump back, and the next click was computed from the old value, so it re-sent a value the
 * server already had: with real network latency, quick clicks were lost. While edits are outstanding,
 * the values this client sent are kept over a catalog's; once the last one is answered, the next
 * catalog is the server's truth again (and also shows refused edits reverted).
 *
 * <p>If the server never answers (an older server, a dropped packet) the kept values expire after
 * {@link #TIMEOUT_MS}.
 */
public final class PendingEdits {
	public static final long TIMEOUT_MS = 5_000;

	private final LongSupplier clock;
	private final Map<String, Map<String, String>> sent = new HashMap<>();
	private int outstanding;
	private long lastSent;

	public PendingEdits() {
		this(System::currentTimeMillis);
	}

	PendingEdits(LongSupplier clock) {
		this.clock = clock;
	}

	/** One edit message was sent, changing {@code values} (key to value text) of {@code configId}. */
	public synchronized void sent(String configId, Map<String, String> values) {
		expire();
		outstanding++;
		lastSent = clock.getAsLong();
		sent.computeIfAbsent(configId, k -> new HashMap<>()).putAll(values);
	}

	/** The server answered one edit (accepted or refused). */
	public synchronized void answered() {
		if (outstanding > 0 && --outstanding == 0) {
			sent.clear();
		}
	}

	/** Values to show instead of a catalog's for {@code configId} (key to value text); empty if none are pending. */
	public synchronized Map<String, String> overrides(String configId) {
		expire();
		Map<String, String> values = sent.get(configId);
		return values == null ? Map.of() : Map.copyOf(values);
	}

	public synchronized boolean isPending() {
		expire();
		return outstanding > 0;
	}

	public synchronized void clear() {
		outstanding = 0;
		sent.clear();
	}

	private void expire() {
		if (outstanding > 0 && clock.getAsLong() - lastSent > TIMEOUT_MS) {
			clear();
		}
	}
}
