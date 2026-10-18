package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects a client claiming to stand on the ground while nothing is below it (ground spoof; also
 * what most NoFall cheats do to avoid fall damage).
 *
 * <p>Evidence: the {@code onGround} flag of the latest movement packet versus the server's own
 * collision test of a box from the player's feet to 0.5 blocks below, widened by 0.1 blocks.
 *
 * <p>Latency handling: a legitimate client can keep claiming ground for as long as it has not yet
 * received a block change (e.g. the block under it was just broken).
 * <ul>
 * <li>Synced: claims only count once the client answered a ping sent after the server tick at which
 * the ground was found missing, so a network stall never adds to the streak.</li>
 * <li>Unsynced (no recent pong): claims count by their arrival time, at most one tick's worth each,
 * so the burst a client sends after a stall counts as the short time it took to arrive.</li>
 * <li>Either way a flag then needs {@code 6 + ceil(recent max RTT / 50 ms)} ticks of the claim; if
 * the RTT is unknown, one second is assumed.</li>
 * </ul>
 */
public final class GroundSpoofCheck {
	public static final String ID = "movement.ground_spoof";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	static final int BASE_TICKS = 6;
	static final int UNKNOWN_RTT_MS = 1000;
	static final double TICK_MS = 50;

	public static final class State {
		/** Ticks of evidence so far (arrival time in ticks while unsynced). */
		double streak;
		/** Whether a claim in the air is in progress, the server tick it was first seen and its last arrival. */
		boolean inAir;
		int lostTick;
		long lastNanos;
	}

	private GroundSpoofCheck() {
	}

	public static int requiredTicks(int recentMaxRttMs) {
		int rtt = recentMaxRttMs < 0 ? UNKNOWN_RTT_MS : recentMaxRttMs;
		return BASE_TICKS + (int) Math.ceil(rtt / 50.0);
	}

	/**
	 * Called once per client tick.
	 *
	 * @param claimedOnGround onGround flag of the client's latest movement packet
	 * @param groundBelow     whether the server finds collision under the player
	 * @param exempt          flying, climbing, swimming, riding, awaiting a teleport...
	 * @param serverTick      the server's current tick
	 * @param ackTick         newest server tick the client confirmed (tick sync), empty if unsynced
	 * @param nowNanos        when this client tick is handled
	 */
	public static Optional<Flag> onClientTick(State s, boolean claimedOnGround, boolean groundBelow, boolean exempt,
			int recentMaxRttMs, double y, int serverTick, OptionalInt ackTick, long nowNanos) {
		if (exempt || !claimedOnGround || groundBelow) {
			s.streak = 0;
			s.inAir = false;
			return Optional.empty();
		}
		if (!s.inAir) {
			s.inAir = true;
			s.lostTick = serverTick;
			s.lastNanos = nowNanos;
		}
		double gapMs = Math.max(0, nowNanos - s.lastNanos) / 1_000_000.0;
		s.lastNanos = nowNanos;
		if (ackTick.isPresent()) {
			// "> lostTick": a block removed between ticks is only sent with the next tick, before its ping.
			if (ackTick.getAsInt() <= s.lostTick) {
				return Optional.empty(); // the client may not have seen the ground go yet
			}
			s.streak++;
		} else {
			s.streak += Math.min(gapMs, TICK_MS) / TICK_MS;
		}
		int required = requiredTicks(recentMaxRttMs);
		if (s.streak < required) {
			return Optional.empty();
		}
		s.streak = 0;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ticks", required);
		m.put("rtt_max_ms", recentMaxRttMs);
		m.put("synced", ackTick.isPresent());
		m.put("y", y);
		return Optional.of(new Flag(ID, "claimed_ground_in_air", Confidence.HIGH, 1.0, m));
	}
}
