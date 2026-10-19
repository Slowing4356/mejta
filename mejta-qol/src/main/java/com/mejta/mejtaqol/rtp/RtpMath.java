package com.mejta.mejtaqol.rtp;

import java.util.Optional;
import java.util.random.RandomGenerator;

/** The Minecraft-free part of random teleport: where the search is centred and which columns it tries. */
public final class RtpMath {
	/** A block column. */
	public record Column(int x, int z) {
	}

	/** The search centre: the world spawn, or fixed coordinates. */
	public sealed interface Center {
		record Spawn() implements Center {
		}

		record Fixed(int x, int z) implements Center {
		}
	}

	private RtpMath() {
	}

	/** Parses {@code spawn} or {@code <x>,<z>} (e.g. {@code 0,0} or {@code 1500, -200}). */
	public static Optional<Center> parseCenter(String text) {
		if (text == null) {
			return Optional.empty();
		}
		String t = text.trim();
		if (t.equalsIgnoreCase("spawn")) {
			return Optional.of(new Center.Spawn());
		}
		String[] parts = t.split(",");
		if (parts.length != 2) {
			return Optional.empty();
		}
		try {
			int x = Integer.parseInt(parts[0].trim());
			int z = Integer.parseInt(parts[1].trim());
			if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
				return Optional.empty();
			}
			return Optional.of(new Center.Fixed(x, z));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * A random column whose horizontal distance from the centre lies between {@code minRadius} and
	 * {@code maxRadius} (swapped if given the wrong way round). Uniform over the ring's area, so points far
	 * out are as likely as near ones per block.
	 */
	public static Column randomColumn(int centerX, int centerZ, int minRadius, int maxRadius, RandomGenerator random) {
		int lo = Math.max(0, Math.min(minRadius, maxRadius));
		int hi = Math.max(0, Math.max(minRadius, maxRadius));
		double r = Math.sqrt(lo * (double) lo + random.nextDouble() * (hi * (double) hi - lo * (double) lo));
		double angle = random.nextDouble() * Math.PI * 2;
		long x = centerX + Math.round(r * Math.cos(angle));
		long z = centerZ + Math.round(r * Math.sin(angle));
		return new Column((int) Math.clamp(x, -29_999_984L, 29_999_984L), (int) Math.clamp(z, -29_999_984L, 29_999_984L));
	}
}
