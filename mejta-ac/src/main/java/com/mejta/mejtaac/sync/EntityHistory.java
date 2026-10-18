package com.mejta.mejtaac.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Bounding boxes of entities near players for the last {@value #TICKS} server ticks, so a hit can be
 * judged against where the target was when the client saw it. Only entities within
 * {@value #RADIUS} blocks of a player are tracked; memory is bounded by players × nearby entities
 * × {@value #TICKS}. Server thread only.
 */
public final class EntityHistory {
	public static final int TICKS = 40;
	/** Must exceed any attack range we judge; attacks with a larger range are skipped. */
	public static final double RADIUS = 10.0;

	private static final class Track {
		final AABB[] boxes = new AABB[TICKS];
		final int[] ticks = new int[TICKS];
		int lastTick = Integer.MIN_VALUE;

		Track() {
			java.util.Arrays.fill(ticks, Integer.MIN_VALUE);
		}
	}

	private final Map<Integer, Track> tracks = new HashMap<>();

	/** End of every server tick: records entities near each player. */
	public void record(MinecraftServer server, int tick) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(RADIUS))) {
				record(entity.getId(), entity.getBoundingBox(), tick);
			}
		}
		if (tick % 20 == 0) {
			tracks.values().removeIf(track -> tick - track.lastTick > TICKS);
		}
	}

	public void record(int entityId, AABB box, int tick) {
		Track track = tracks.computeIfAbsent(entityId, k -> new Track());
		int slot = Math.floorMod(tick, TICKS);
		track.boxes[slot] = box;
		track.ticks[slot] = tick;
		track.lastTick = Math.max(track.lastTick, tick);
	}

	/** Recorded boxes with {@code fromTick <= tick <= toTick}, oldest first. */
	public List<AABB> boxes(int entityId, int fromTick, int toTick) {
		List<AABB> out = new ArrayList<>();
		Track track = tracks.get(entityId);
		if (track == null) {
			return out;
		}
		for (int tick = Math.max(fromTick, toTick - TICKS + 1); tick <= toTick; tick++) {
			int slot = Math.floorMod(tick, TICKS);
			if (track.ticks[slot] == tick) {
				out.add(track.boxes[slot]);
			}
		}
		return out;
	}

	/** Forgets everything, e.g. when the server stops (a new one reuses entity IDs and ticks). */
	public void clear() {
		tracks.clear();
	}

	public int trackedEntities() {
		return tracks.size();
	}
}
