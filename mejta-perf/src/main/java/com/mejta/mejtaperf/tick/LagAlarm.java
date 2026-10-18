package com.mejta.mejtaperf.tick;

/**
 * Decides when lag is worth an alert. Fed about once per second with the mean MSPT of the interval since the last feed.
 *
 * <ul>
 * <li>{@link Event#LAG} when MSPT stayed at or above the threshold for the whole sustain time, and the last
 * alert is at least the cooldown ago.</li>
 * <li>{@link Event#RECOVERED} after an alert, once MSPT stayed below 80% of the threshold for the sustain time
 * (the margin stops an alert and a recovery every few seconds when MSPT hovers at the threshold).</li>
 * </ul>
 * Server thread only.
 */
public final class LagAlarm {
	public enum Event {
		NONE, LAG, RECOVERED
	}

	private static final double RECOVERY_FACTOR = 0.8;

	private double thresholdMs;
	private long sustainNanos;
	private long cooldownNanos;

	private long overSince = -1;
	private long underSince = -1;
	private boolean lagging;
	private long lastAlert;
	private boolean alerted;
	private long lagStart;
	private double peakMs;

	public LagAlarm(double thresholdMs, long sustainNanos, long cooldownNanos) {
		configure(thresholdMs, sustainNanos, cooldownNanos);
	}

	public void configure(double thresholdMs, long sustainNanos, long cooldownNanos) {
		this.thresholdMs = thresholdMs;
		this.sustainNanos = Math.max(0, sustainNanos);
		this.cooldownNanos = Math.max(0, cooldownNanos);
	}

	/**
	 * @param from start of the measured interval: usually a second ago, earlier when a single tick took longer
	 * @param mspt mean milliseconds per tick over the interval; NaN is ignored
	 */
	public Event feed(long now, long from, double mspt) {
		if (Double.isNaN(mspt)) {
			return Event.NONE;
		}
		if (mspt >= thresholdMs) {
			underSince = -1;
			if (overSince < 0) {
				overSince = Math.min(now, from);
				peakMs = 0;
			}
			peakMs = Math.max(peakMs, mspt);
			if (!lagging && now - overSince >= sustainNanos && (!alerted || now - lastAlert >= cooldownNanos)) {
				lagging = true;
				alerted = true;
				lastAlert = now;
				lagStart = overSince;
				return Event.LAG;
			}
			return Event.NONE;
		}
		overSince = lagging ? overSince : -1;
		if (!lagging) {
			return Event.NONE;
		}
		if (mspt >= thresholdMs * RECOVERY_FACTOR) {
			underSince = -1;
			return Event.NONE;
		}
		if (underSince < 0) {
			underSince = Math.min(now, from);
		}
		if (now - underSince >= sustainNanos) {
			lagging = false;
			underSince = -1;
			overSince = -1;
			return Event.RECOVERED;
		}
		return Event.NONE;
	}

	/** Forgets all state, for example when a new server starts. Settings are kept. */
	public void reset() {
		overSince = -1;
		underSince = -1;
		lagging = false;
		alerted = false;
		peakMs = 0;
	}

	public boolean lagging() {
		return lagging;
	}

	/** Highest one-second MSPT of the current (or last) lag. */
	public double peakMs() {
		return peakMs;
	}

	/** How long the current (or last) lag has lasted, measured from when MSPT first crossed the threshold. */
	public long lagNanos(long now) {
		return now - lagStart;
	}
}
