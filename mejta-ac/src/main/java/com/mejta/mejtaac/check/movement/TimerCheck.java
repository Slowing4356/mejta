package com.mejta.mejtaac.check.movement;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects a client running more ticks than real time allows ("timer").
 *
 * <p>Evidence: the vanilla client sends exactly one {@code ServerboundClientTickEndPacket} per client
 * tick, even when standing still. Each one costs one tick's worth of time (50 ms at the normal tick
 * rate); real time between arrivals adds credit. A client that is consistently ahead goes into debt.
 *
 * <p>Latency handling:
 * <ul>
 * <li>Arrival times are taken on the network thread, so server lag does not bunch them up.</li>
 * <li>Jitter: up to {@value #CREDIT_CAP_MS} ms of credit is kept, so packets arriving early after a
 * late one never matter.</li>
 * <li>Lag spikes: after a gap longer than {@value #GAP_MS} ms (network stall or client freeze), the
 * vanilla client catches up by sending the missed ticks in a burst. The whole gap becomes
 * temporary "lag credit" that the burst may spend; it expires after {@value #LAG_CREDIT_TTL_MS} ms
 * so it cannot be saved up for later speed-ups.</li>
 * <li>Gradual lateness (a network delay that grows, then drains): real time beyond the cap is kept
 * as well, each part for {@value #LAG_CREDIT_TTL_MS} ms after it was earned, but only arrivals
 * less than {@value #BURST_MS} ms apart (a catch-up burst) may spend it. Slowing down, then
 * running fast at an even pace, is therefore still flagged.</li>
 * </ul>
 * A client must be more than {@code maxAheadMs} beyond all that before a flag.
 */
public final class TimerCheck {
	public static final String ID = "movement.timer";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	static final double CREDIT_CAP_MS = 1000;
	static final double GAP_MS = 300;
	static final long LAG_CREDIT_TTL_MS = 2000;
	/** Arrivals closer together than this are a catch-up burst. */
	static final double BURST_MS = 10;
	/** Bound on stored parts of gradual lateness (about one per late arrival within the expiry). */
	private static final int MAX_LATE_PARTS = 64;

	/** One part of gradual lateness and when it expires. */
	private static final class Late {
		double ms;
		final long expiresNanos;

		Late(double ms, long expiresNanos) {
			this.ms = ms;
			this.expiresNanos = expiresNanos;
		}
	}

	/** Per-player state. Guarded by its own monitor (updated on the network thread). */
	public static final class State {
		double balanceMs = CREDIT_CAP_MS;
		double lagCreditMs;
		long lagCreditExpiresNanos;
		/** Gradual lateness, oldest first; only catch-up bursts spend it. */
		final ArrayDeque<Late> late = new ArrayDeque<>();
		long lastNanos = Long.MIN_VALUE;

		/** Forget timing, e.g. after a tick-rate change. */
		public synchronized void reset() {
			balanceMs = CREDIT_CAP_MS;
			lagCreditMs = 0;
			late.clear();
			lastNanos = Long.MIN_VALUE;
		}
	}

	private TimerCheck() {
	}

	/**
	 * Accounts for one client tick that arrived at {@code nowNanos}.
	 *
	 * @param msPerTick  the server's current tick length (the client follows the server's tick rate)
	 * @param maxAheadMs how far ahead of real time a client may get before a flag
	 */
	public static Optional<Flag> onClientTick(State s, long nowNanos, double msPerTick, int maxAheadMs) {
		synchronized (s) {
			if (s.lastNanos == Long.MIN_VALUE) {
				s.lastNanos = nowNanos;
				return Optional.empty();
			}
			double elapsed = Math.max(0, (nowNanos - s.lastNanos) / 1_000_000.0);
			s.lastNanos = nowNanos;

			s.balanceMs += elapsed;
			if (s.balanceMs > CREDIT_CAP_MS) {
				double overflow = s.balanceMs - CREDIT_CAP_MS;
				s.balanceMs = CREDIT_CAP_MS;
				long expires = nowNanos + LAG_CREDIT_TTL_MS * 1_000_000L;
				if (elapsed > GAP_MS) {
					s.lagCreditMs += overflow;
					s.lagCreditExpiresNanos = expires;
				} else if (s.late.size() < MAX_LATE_PARTS) {
					s.late.addLast(new Late(overflow, expires));
				} else {
					s.late.peekLast().ms += overflow; // expires a little early: never in the client's favour
				}
			}
			if (s.lagCreditMs > 0 && nowNanos > s.lagCreditExpiresNanos) {
				s.lagCreditMs = 0;
			}
			while (!s.late.isEmpty() && nowNanos > s.late.peekFirst().expiresNanos) {
				s.late.pollFirst();
			}

			double fromLag = Math.min(s.lagCreditMs, msPerTick);
			s.lagCreditMs -= fromLag;
			double cost = msPerTick - fromLag;
			if (elapsed < BURST_MS) {
				while (cost > 0 && !s.late.isEmpty()) {
					Late oldest = s.late.peekFirst();
					double take = Math.min(oldest.ms, cost);
					oldest.ms -= take;
					cost -= take;
					if (oldest.ms <= 0) {
						s.late.pollFirst();
					}
				}
			}
			s.balanceMs -= cost;

			if (s.balanceMs < -maxAheadMs) {
				double ahead = -s.balanceMs;
				s.balanceMs = 0;
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("ahead_ms", Math.round(ahead));
				m.put("ms_per_tick", msPerTick);
				return Optional.of(new Flag(ID, "too_many_ticks", Confidence.HIGH, 1.0, m));
			}
			return Optional.empty();
		}
	}
}
