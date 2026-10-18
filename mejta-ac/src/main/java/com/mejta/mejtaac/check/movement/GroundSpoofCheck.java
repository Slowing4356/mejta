package com.mejta.mejtaac.check.movement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * received a block change (e.g. the block under it was just broken), which is up to one round trip.
 * So a flag needs {@code 6 + ceil(recent max RTT / 50 ms)} consecutive client ticks of the claim; if
 * the RTT is unknown, one second is assumed.
 */
public final class GroundSpoofCheck {
	public static final String ID = "movement.ground_spoof";
	public static final CheckCategory CATEGORY = CheckCategory.MOVEMENT;

	static final int BASE_TICKS = 6;
	static final int UNKNOWN_RTT_MS = 1000;

	public static final class State {
		int streak;
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
	 */
	public static Optional<Flag> onClientTick(State s, boolean claimedOnGround, boolean groundBelow, boolean exempt,
			int recentMaxRttMs, double y) {
		if (exempt || !claimedOnGround || groundBelow) {
			s.streak = 0;
			return Optional.empty();
		}
		s.streak++;
		int required = requiredTicks(recentMaxRttMs);
		if (s.streak < required) {
			return Optional.empty();
		}
		s.streak = 0;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("ticks", required);
		m.put("rtt_max_ms", recentMaxRttMs);
		m.put("y", y);
		return Optional.of(new Flag(ID, "claimed_ground_in_air", Confidence.HIGH, 1.0, m));
	}
}
