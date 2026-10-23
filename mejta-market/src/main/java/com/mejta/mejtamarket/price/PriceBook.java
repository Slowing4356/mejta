package com.mejta.mejtamarket.price;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reference prices of items, worked out from what players really paid each other.
 *
 * <p>Built to be hard to manipulate:
 * <ul>
 * <li>Only trades between two different players count; the bot's own trades never do.</li>
 * <li>All trades of one buyer and seller pair on one day count as one sample (their median), so two
 * accounts trading back and forth add a single sample per day.</li>
 * <li>Until {@link Params#minSamples} samples exist, the price list's price is used.</li>
 * <li>The samples' top and bottom fifth are dropped before the median is taken.</li>
 * <li>A reference price stays within {@link Params#band} of the price list's price, and moves at most
 * {@link Params#maxDailyChange} per day towards what trades say.</li>
 * </ul>
 * Not thread-safe; used on the server thread.
 */
public final class PriceBook {
	/** Stands for the market bot as buyer or seller. */
	public static final UUID BOT = new UUID(0L, 0x6d61726b6574L);
	static final long DAY = 86_400_000L;
	/** Trades kept per item, newest last. */
	static final int MAX_TRADES = 256;

	/**
	 * @param historyDays    trades older than this are ignored
	 * @param minSamples     samples needed before trades count
	 * @param maxDailyChange e.g. {@code 0.10} for at most 10 % per day
	 * @param band           e.g. {@code 2.0}: between half and twice the listed price
	 */
	public record Params(int historyDays, int minSamples, double maxDailyChange, double band) {
	}

	public record Trade(long millis, long unitPrice, UUID buyer, UUID seller) {
		public boolean countsForPrices() {
			return !buyer.equals(seller) && !buyer.equals(BOT) && !seller.equals(BOT) && unitPrice > 0;
		}
	}

	private record State(long price, long millis) {
	}

	private final Map<String, Deque<Trade>> trades = new HashMap<>();
	private final Map<String, State> references = new HashMap<>();

	/** Notes a sale of {@code count} items for {@code totalPrice} in all. */
	public void record(String item, long millis, long totalPrice, int count, UUID buyer, UUID seller) {
		if (count <= 0 || totalPrice <= 0) {
			return;
		}
		Deque<Trade> list = trades.computeIfAbsent(item, k -> new ArrayDeque<>());
		list.addLast(new Trade(millis, Math.max(1, totalPrice / count), buyer, seller));
		while (list.size() > MAX_TRADES) {
			list.removeFirst();
		}
	}

	/** What recent player trades say an item is worth, or empty without enough samples. */
	public OptionalLong observed(String item, long now, Params params) {
		Deque<Trade> list = trades.get(item);
		if (list == null) {
			return OptionalLong.empty();
		}
		long since = now - params.historyDays() * DAY;
		Map<String, List<Long>> groups = new HashMap<>();
		for (Trade t : list) {
			if (t.millis() >= since && t.millis() <= now && t.countsForPrices()) {
				groups.computeIfAbsent(t.buyer() + "/" + t.seller() + "/" + Math.floorDiv(t.millis(), DAY), k -> new ArrayList<>()).add(t.unitPrice());
			}
		}
		if (groups.size() < params.minSamples()) {
			return OptionalLong.empty();
		}
		List<Long> samples = new ArrayList<>(groups.size());
		for (List<Long> g : groups.values()) {
			samples.add(median(g));
		}
		Collections.sort(samples);
		int trim = samples.size() / 5;
		return OptionalLong.of(median(samples.subList(trim, samples.size() - trim)));
	}

	/**
	 * The item's reference price now, moved towards what trades say as far as the daily limit allows.
	 *
	 * @param listed the price list's price, if the item has one
	 * @return empty if the item has neither a listed price nor enough trades
	 */
	public OptionalLong reference(String item, long now, OptionalLong listed, Params params) {
		OptionalLong seen = observed(item, now, params);
		long target;
		if (seen.isPresent()) {
			target = seen.getAsLong();
		} else if (listed.isPresent()) {
			target = listed.getAsLong();
		} else {
			State old = references.get(item);
			return old == null ? OptionalLong.empty() : OptionalLong.of(old.price());
		}
		if (listed.isPresent() && listed.getAsLong() > 0) {
			long base = listed.getAsLong();
			target = clamp(target, Math.max(1, (long) Math.floor(base / params.band())), (long) Math.ceil(base * params.band()));
		}
		State old = references.get(item);
		long price;
		if (old == null) {
			// The first reference is the listed price when there is one: trades only ever nudge it.
			price = listed.isPresent() ? listed.getAsLong() : target;
		} else {
			double days = Math.max(0, now - old.millis()) / (double) DAY;
			double factor = Math.pow(1.0 + params.maxDailyChange(), days);
			// Rounded towards the old price, so frequent calls cannot each sneak in a whole extra unit.
			long low = Math.max(1, Math.min(old.price(), (long) Math.ceil(old.price() / factor)));
			long high = Math.max(old.price(), (long) Math.floor(old.price() * factor));
			price = clamp(target, low, high);
			if (price == old.price()) {
				// Unchanged: keep the old time, so slow drifts still add up across many small steps.
				return OptionalLong.of(price);
			}
		}
		references.put(item, new State(price, now));
		return OptionalLong.of(price);
	}

	/** The last reference price worked out for the item, without updating it. */
	public OptionalLong lastReference(String item) {
		State s = references.get(item);
		return s == null ? OptionalLong.empty() : OptionalLong.of(s.price());
	}

	/** Trades of the item still within the history window, newest first (for the price history view). */
	public List<Trade> recent(String item, long now, int historyDays, int limit) {
		Deque<Trade> list = trades.get(item);
		List<Trade> out = new ArrayList<>();
		if (list == null) {
			return out;
		}
		long since = now - historyDays * DAY;
		var it = list.descendingIterator();
		while (it.hasNext() && out.size() < limit) {
			Trade t = it.next();
			if (t.millis() >= since) {
				out.add(t);
			}
		}
		return out;
	}

	/** Drops trades older than the history window and items left without any. */
	public void prune(long now, int historyDays) {
		long since = now - historyDays * DAY;
		trades.values().forEach(list -> list.removeIf(t -> t.millis() < since));
		trades.values().removeIf(Deque::isEmpty);
	}

	static long median(List<Long> values) {
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int n = sorted.size();
		if (n % 2 == 1) {
			return sorted.get(n / 2);
		}
		long a = sorted.get(n / 2 - 1);
		long b = sorted.get(n / 2);
		return a + (b - a) / 2;
	}

	private static long clamp(long value, long low, long high) {
		return Math.max(low, Math.min(high, value));
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		JsonObject t = new JsonObject();
		trades.forEach((item, list) -> {
			JsonArray arr = new JsonArray();
			for (Trade trade : list) {
				JsonArray row = new JsonArray();
				row.add(trade.millis());
				row.add(trade.unitPrice());
				row.add(trade.buyer().toString());
				row.add(trade.seller().toString());
				arr.add(row);
			}
			t.add(item, arr);
		});
		root.add("trades", t);
		JsonObject r = new JsonObject();
		references.forEach((item, s) -> {
			JsonArray row = new JsonArray();
			row.add(s.price());
			row.add(s.millis());
			r.add(item, row);
		});
		root.add("references", r);
		return root;
	}

	/** Reads what {@link #toJson} wrote; damaged entries are skipped. */
	public static PriceBook fromJson(JsonObject root) {
		PriceBook book = new PriceBook();
		if (root.has("trades") && root.get("trades").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("trades").entrySet()) {
				if (!e.getValue().isJsonArray()) {
					continue;
				}
				Deque<Trade> list = new ArrayDeque<>();
				for (JsonElement row : e.getValue().getAsJsonArray()) {
					try {
						JsonArray a = row.getAsJsonArray();
						list.addLast(new Trade(a.get(0).getAsLong(), a.get(1).getAsLong(), UUID.fromString(a.get(2).getAsString()),
								UUID.fromString(a.get(3).getAsString())));
					} catch (RuntimeException ignored) {
						// damaged row: skipped
					}
				}
				if (!list.isEmpty()) {
					book.trades.put(e.getKey(), list);
				}
			}
		}
		if (root.has("references") && root.get("references").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("references").entrySet()) {
				try {
					JsonArray a = e.getValue().getAsJsonArray();
					book.references.put(e.getKey(), new State(a.get(0).getAsLong(), a.get(1).getAsLong()));
				} catch (RuntimeException ignored) {
					// damaged entry: worked out again
				}
			}
		}
		return book;
	}
}
