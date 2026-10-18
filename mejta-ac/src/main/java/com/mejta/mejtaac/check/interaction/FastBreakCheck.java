package com.mejta.mejtaac.check.interaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtaac.check.CheckCategory;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;

/**
 * Detects blocks broken faster than the player's tool, enchantments and effects allow.
 *
 * <p>The vanilla client adds the block's destroy progress once per client tick and finishes when the
 * sum reaches 1, so it needs {@code ceil(1 / progressPerTick)} ticks. The check counts the client's
 * own ticks (its tick-end packets) between "start" and "finish", so latency and jitter cannot shorten
 * the measured time. The rate is taken in the player's favour: the faster of the start and finish
 * moments, without the airborne and underwater penalties. Vanilla itself refuses breaks below 70 %
 * progress, so this mostly catches the remaining 30 % speed-up, and the attempts below it.
 */
public final class FastBreakCheck {
	public static final String ID = "interaction.fast_break";
	public static final CheckCategory CATEGORY = CheckCategory.INTERACTION;

	/** Client ticks of slack (tick boundaries, rounding). */
	static final int SLACK_TICKS = 2;

	private FastBreakCheck() {
	}

	public static int expectedTicks(double progressPerTick) {
		return progressPerTick <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1.0 / progressPerTick - 1.0E-6);
	}

	/**
	 * @param progressPerTick the most favourable destroy progress per tick
	 * @param clientTicks     client ticks between starting and finishing
	 */
	public static Optional<Flag> onFinish(double progressPerTick, int clientTicks) {
		int expected = expectedTicks(progressPerTick);
		if (expected <= 1 || expected == Integer.MAX_VALUE || clientTicks >= expected - SLACK_TICKS) {
			return Optional.empty();
		}
		boolean high = expected >= 5 && clientTicks < expected * 0.7 - 1;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("expected_ticks", expected);
		m.put("actual_ticks", clientTicks);
		m.put("progress_per_tick", Math.round(progressPerTick * 10000.0) / 10000.0);
		return Optional.of(new Flag(ID, "too_fast", high ? Confidence.HIGH : Confidence.MEDIUM, high ? 1.0 : 0.5, m));
	}
}
