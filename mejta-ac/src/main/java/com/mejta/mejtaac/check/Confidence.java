package com.mejta.mejtaac.check;

/**
 * How sure a check is that a flag means cheating. Enforcement only acts on flags at or above the
 * configured minimum; lower-confidence flags still raise violation levels, alerts and records.
 */
public enum Confidence {
	/** Ambiguous signal; legitimate play or lag can explain it. */
	LOW,
	/** Suspicious but explainable by rare legitimate situations. */
	MEDIUM,
	/** Very unlikely to be legitimate. */
	HIGH,
	/** Impossible for an unmodified client (e.g. values the vanilla client can never send). */
	CERTAIN;

	public boolean atLeast(Confidence other) {
		return ordinal() >= other.ordinal();
	}
}
