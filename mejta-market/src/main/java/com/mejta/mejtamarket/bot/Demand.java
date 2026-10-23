package com.mejta.mejtamarket.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What players look for in the market: each search adds interest to the price-list items it matches,
 * and interest halves every {@link #HALF_LIFE} so old searches fade. The bot lists items with more
 * interest more often, and soon restocks an item someone searched for that nobody sells.
 *
 * <p>One player counts at most once per item per {@link #PER_PLAYER_GAP}, so typing the same search
 * again and again does not make an item look popular. Not thread-safe; used on the server thread.
 */
public final class Demand {
	static final long HALF_LIFE = 6 * 3_600_000L;
	static final long PER_PLAYER_GAP = 10 * 60_000L;
	/** Items whose interest fell below this are forgotten. */
	static final double FORGET_BELOW = 0.05;

	private record Interest(double value, long millis) {
		double at(long now) {
			return value * Math.pow(0.5, Math.max(0, now - millis) / (double) HALF_LIFE);
		}
	}

	private final Map<String, Interest> interest = new HashMap<>();
	/** player + item → when they last counted. Not saved: a restart only forgets who counted recently. */
	private final Map<String, Long> lastCounted = new HashMap<>();

	/** Notes that {@code player} looked for {@code item}. @return whether it counted */
	public boolean searched(UUID player, String item, long now) {
		String key = player + "/" + item;
		Long last = lastCounted.get(key);
		if (last != null && now - last < PER_PLAYER_GAP) {
			return false;
		}
		lastCounted.put(key, now);
		interest.put(item, new Interest(of(item, now) + 1.0, now));
		return true;
	}

	/** The item's interest now: about the number of recent searches for it, fading over time. */
	public double of(String item, long now) {
		Interest i = interest.get(item);
		return i == null ? 0 : i.at(now);
	}

	/** How strongly the bot prefers to list this item: 1 for no interest, up to 9 for much. */
	public double weight(String item, long now) {
		return 1.0 + Math.min(8.0, 2.0 * of(item, now));
	}

	/** The item with the most interest now, if any has some. */
	public Optional<String> top(long now) {
		String best = null;
		double bestValue = FORGET_BELOW;
		for (String item : interest.keySet()) {
			double v = of(item, now);
			if (v > bestValue) {
				bestValue = v;
				best = item;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Forgets faded interest and old per-player marks. */
	public void prune(long now) {
		interest.entrySet().removeIf(e -> e.getValue().at(now) < FORGET_BELOW);
		lastCounted.values().removeIf(t -> now - t >= PER_PLAYER_GAP);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		interest.forEach((item, i) -> {
			JsonArray a = new JsonArray();
			a.add(i.value());
			a.add(i.millis());
			o.add(item, a);
		});
		return o;
	}

	public static Demand fromJson(JsonObject o) {
		Demand d = new Demand();
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			try {
				JsonArray a = e.getValue().getAsJsonArray();
				d.interest.put(e.getKey(), new Interest(a.get(0).getAsDouble(), a.get(1).getAsLong()));
			} catch (RuntimeException ignored) {
				// damaged entry: forgotten
			}
		}
		return d;
	}
}
