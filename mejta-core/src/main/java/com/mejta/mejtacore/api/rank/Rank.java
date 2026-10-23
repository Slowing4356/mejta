package com.mejta.mejtacore.api.rank;

import java.util.Locale;
import java.util.Optional;

/**
 * A player's rank, from {@link #MEMBER} (everyone) up to {@link #GOLD}. Ranks are ordered: each one keeps the perks of
 * the ranks below it, so check them with {@link #atLeast}.
 *
 * <p>Ranks belong to the player's account, not to one game mode: every addon decides for itself what a rank gives
 * (more homes, a shorter cooldown, a cosmetic), and nothing a rank gives should make a player stronger than others.
 *
 * @since API version 8
 */
public enum Rank {
	MEMBER("Member"),
	BRONZE("Bronze"),
	SILVER("Silver"),
	GOLD("Gold");

	private final String displayName;

	Rank(String displayName) {
		this.displayName = displayName;
	}

	/** {@code "Bronze"}. */
	public String displayName() {
		return displayName;
	}

	/** {@code "bronze"}, as typed in commands and stored in files. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Whether this rank is {@code other} or higher. */
	public boolean atLeast(Rank other) {
		return ordinal() >= other.ordinal();
	}

	/** The rank with this {@link #id()} (any case), or empty. */
	public static Optional<Rank> byId(String id) {
		if (id == null) {
			return Optional.empty();
		}
		for (Rank rank : values()) {
			if (rank.id().equalsIgnoreCase(id.trim())) {
				return Optional.of(rank);
			}
		}
		return Optional.empty();
	}
}
