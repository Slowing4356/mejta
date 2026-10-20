package com.mejta.mejtasmp.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;

import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.util.ScopedDataStore;

/**
 * Every player's numbers, for sidebars and leaderboards: kept in memory, and in the data store
 * ({@code stats/<uuid>}) so offline players stay on the leaderboards. Server thread only.
 */
public final class StatBook {
	static final String FOLDER = "stats";
	/** Play time alone is saved at most this often (5 minutes of it); everything else at once. */
	static final long PLAY_TIME_SAVE_TICKS = 6000;

	private final ScopedDataStore store;
	private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
	private final Map<UUID, PlayerStats> saved = new ConcurrentHashMap<>();

	public StatBook(ScopedDataStore store) {
		this.store = store;
	}

	/** Server start: loads everyone's numbers. */
	public void load() {
		stats.clear();
		for (String key : store.keys(FOLDER)) {
			try {
				UUID id = UUID.fromString(key.substring(FOLDER.length() + 1));
				store.read(key).ifPresent(json -> {
					PlayerStats s = PlayerStats.fromJson(id, json);
					stats.put(id, s);
					saved.put(id, s);
				});
			} catch (RuntimeException e) {
				MejtaSMP.log().warn("Skipped damaged stats record {}", key);
			}
		}
	}

	public void clear() {
		stats.clear();
		saved.clear();
	}

	/** A player left: their latest numbers are saved. */
	public void leave(ServerPlayer player) {
		PlayerStats now = refresh(player);
		save(now);
	}

	/** Takes the game's statistics of an online player. Saved when they changed. */
	public PlayerStats refresh(ServerPlayer player) {
		ServerStatsCounter counter = player.getStats();
		PlayerStats before = stats.getOrDefault(player.getUUID(), PlayerStats.empty(player.getUUID(), player.getGameProfile().name()));
		PlayerStats after = before.withGameStats(player.getGameProfile().name(),
				counter.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)), counter.getValue(Stats.CUSTOM.get(Stats.DEATHS)),
				counter.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)), counter.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
		put(after);
		return after;
	}

	/** A player killed another: the killer's streak grows, the victim's ends. */
	public void playerKilled(ServerPlayer killer, ServerPlayer victim) {
		put(refresh(killer).killed());
		died(victim);
	}

	public void died(ServerPlayer victim) {
		put(refresh(victim).died());
	}

	public Optional<PlayerStats> get(UUID player) {
		return Optional.ofNullable(stats.get(player));
	}

	/** The first {@code limit} players by {@code stat}; players with 0 are left out. */
	public List<PlayerStats> top(Stat stat, int limit) {
		List<PlayerStats> list = new ArrayList<>();
		for (PlayerStats s : stats.values()) {
			if (stat.of(s) > 0) {
				list.add(s);
			}
		}
		list.sort(stat.ranking());
		return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
	}

	public int size() {
		return stats.size();
	}

	private void put(PlayerStats after) {
		stats.put(after.id(), after);
		PlayerStats last = saved.get(after.id());
		if (last == null || !after.withPlayTicks(last.playTicks()).equals(last)
				|| after.playTicks() - last.playTicks() >= PLAY_TIME_SAVE_TICKS) {
			save(after);
		}
	}

	private void save(PlayerStats s) {
		saved.put(s.id(), s);
		store.write(FOLDER + "/" + s.id(), s.toJson());
	}
}
