package com.mejta.mejtaperf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Counts of what the server is simulating: players, loaded chunks and entities per dimension, the most common
 * entity types and the chunks with the most entities. Collected on the server thread on demand (a command, an
 * alert); it walks every entity once, so it is not run every tick.
 *
 * <p>These are possible contributors to lag, not a diagnosis.
 */
final class ServerStats {
	record Level(String id, int chunks, int entities, double msPerTick) {
	}

	/** A chunk with many entities: dimension, chunk coordinates and the count. */
	record Crowd(String level, int chunkX, int chunkZ, int entities, String topType) {
	}

	record Snapshot(int players, List<Level> levels, Map<String, Integer> entityTypes, List<Crowd> crowds, int chunks, int entities) {
		/** The {@code n} most common entity types as {@code minecraft:item 1,200, ...}. */
		String topTypes(int n) {
			List<String> parts = new ArrayList<>();
			entityTypes.entrySet().stream().limit(n).forEach(e -> parts.add(e.getKey() + " " + Fmt.count(e.getValue())));
			return parts.isEmpty() ? "none" : String.join(", ", parts);
		}
	}

	private ServerStats() {
	}

	static Snapshot collect(MinecraftServer server, Map<String, Double> levelTimes) {
		List<Level> levels = new ArrayList<>();
		Map<String, Integer> types = new HashMap<>();
		Map<String, Map<Long, Map<String, Integer>>> byChunk = new HashMap<>();
		int chunks = 0;
		int entities = 0;
		for (ServerLevel level : server.getAllLevels()) {
			String id = level.dimension().identifier().toString();
			int levelChunks = level.getChunkSource().getLoadedChunksCount();
			int levelEntities = 0;
			Map<Long, Map<String, Integer>> chunkCounts = byChunk.computeIfAbsent(id, k -> new HashMap<>());
			for (Entity entity : level.getAllEntities()) {
				levelEntities++;
				String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
				types.merge(type, 1, Integer::sum);
				long chunk = ((long) (entity.getBlockX() >> 4) << 32) | ((entity.getBlockZ() >> 4) & 0xFFFFFFFFL);
				chunkCounts.computeIfAbsent(chunk, k -> new HashMap<>()).merge(type, 1, Integer::sum);
			}
			levels.add(new Level(id, levelChunks, levelEntities, levelTimes.getOrDefault(id, Double.NaN)));
			chunks += levelChunks;
			entities += levelEntities;
		}
		return new Snapshot(server.getPlayerCount(), levels, sortByCount(types), crowds(byChunk, 10), chunks, entities);
	}

	static Map<String, Integer> sortByCount(Map<String, Integer> counts) {
		Map<String, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return sorted;
	}

	private static List<Crowd> crowds(Map<String, Map<Long, Map<String, Integer>>> byChunk, int n) {
		List<Crowd> all = new ArrayList<>();
		byChunk.forEach((level, chunks) -> chunks.forEach((key, types) -> {
			int total = types.values().stream().mapToInt(Integer::intValue).sum();
			String top = sortByCount(types).keySet().iterator().next();
			all.add(new Crowd(level, (int) (key >> 32), (int) (long) key, total, top));
		}));
		all.sort((a, b) -> Integer.compare(b.entities(), a.entities()));
		return List.copyOf(all.subList(0, Math.min(n, all.size())));
	}
}
