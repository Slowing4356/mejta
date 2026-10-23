package com.mejta.mejtacrates.data;

import java.util.List;
import java.util.random.RandomGenerator;

/** Weighted draws. Every reward's chance is its weight divided by the sum of the weights. */
public final class Roll {
	private Roll() {
	}

	public static double totalWeight(List<Reward> rewards) {
		double total = 0;
		for (Reward r : rewards) {
			total += r.weight();
		}
		return total;
	}

	/** The chance of {@code reward} among {@code rewards}, 0 to 1. */
	public static double chance(Reward reward, List<Reward> rewards) {
		double total = totalWeight(rewards);
		return total <= 0 ? 0 : reward.weight() / total;
	}

	/**
	 * The index of the reward that {@code point} (0 inclusive to 1 exclusive) lands on.
	 *
	 * @throws IllegalArgumentException if there are no rewards
	 */
	public static int pick(List<Reward> rewards, double point) {
		if (rewards.isEmpty()) {
			throw new IllegalArgumentException("no rewards to draw from");
		}
		double target = Math.min(Math.max(point, 0), Math.nextDown(1.0)) * totalWeight(rewards);
		double sum = 0;
		for (int i = 0; i < rewards.size(); i++) {
			sum += rewards.get(i).weight();
			if (target < sum) {
				return i;
			}
		}
		return rewards.size() - 1; // rounding at the very top
	}

	public static int pick(List<Reward> rewards, RandomGenerator random) {
		return pick(rewards, random.nextDouble());
	}

	/** {@code 12.5%}, {@code 0.25%}, {@code <0.01%}. */
	public static String percent(double chance) {
		double p = chance * 100;
		if (p >= 10) {
			return String.format(java.util.Locale.ROOT, "%.1f%%", p).replace(".0%", "%");
		}
		if (p >= 0.01) {
			return String.format(java.util.Locale.ROOT, "%.2f%%", p).replace(".00%", "%");
		}
		return "<0.01%";
	}
}
