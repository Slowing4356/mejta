package com.mejta.mejtasmp.stats;

import java.util.Comparator;

/** The numbers MejtaSMP shows on sidebars and leaderboards. */
public enum Stat {
	KILLS("Kills"),
	DEATHS("Deaths"),
	PLAY_TIME("Play time"),
	MOB_KILLS("Mob kills"),
	BEST_STREAK("Best kill streak");

	private final String label;

	Stat(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public long of(PlayerStats stats) {
		return switch (this) {
			case KILLS -> stats.kills();
			case DEATHS -> stats.deaths();
			case PLAY_TIME -> stats.playTicks();
			case MOB_KILLS -> stats.mobKills();
			case BEST_STREAK -> stats.bestStreak();
		};
	}

	/** The value as shown: play time as hours and minutes. */
	public String format(PlayerStats stats) {
		return this == PLAY_TIME ? PlayerStats.formatTicks(stats.playTicks()) : String.valueOf(of(stats));
	}

	/** Highest first; ties by name. */
	public Comparator<PlayerStats> ranking() {
		return Comparator.comparingLong((PlayerStats s) -> of(s)).reversed().thenComparing(PlayerStats::name, String.CASE_INSENSITIVE_ORDER);
	}
}
