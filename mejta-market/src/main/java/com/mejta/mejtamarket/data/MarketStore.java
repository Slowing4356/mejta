package com.mejta.mejtamarket.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * Every lot, buy order and payout, kept in memory and in the data store ({@code lots/<id>},
 * {@code orders/<id>}, {@code payouts/<id>}).
 *
 * <p>Every change that moves items or money is written with {@link DataStore#writeNow} (on disk before
 * this returns), and the in-memory copy changes only once that succeeded. Finished records are first
 * written in a final state ({@link Lot.State#GONE}, {@link BuyOrder.State#CLOSED}, a paid payout) and only
 * then deleted, so a deletion lost in a crash can never bring back items or money.
 *
 * <p>Used on the server thread.
 */
public final class MarketStore {
	static final String LOTS = "lots";
	static final String ORDERS = "orders";
	static final String PAYOUTS = "payouts";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private final DataStore store;
	private final Map<String, Lot> lots = new LinkedHashMap<>();
	private final Map<String, BuyOrder> orders = new LinkedHashMap<>();
	private final Map<String, Payout> payouts = new LinkedHashMap<>();
	private final RandomGenerator random = RandomGenerator.getDefault();

	public MarketStore(DataStore store) {
		this.store = store;
	}

	public DataStore raw() {
		return store;
	}

	/**
	 * Reads every record (server start). Finished records are deleted; damaged files are left alone and
	 * named in the result.
	 */
	public List<String> load() {
		lots.clear();
		orders.clear();
		payouts.clear();
		List<String> damaged = new ArrayList<>();
		loadFolder(LOTS, Lot::fromJson, l -> l.state() == Lot.State.GONE, Lot::id, lots, damaged);
		loadFolder(ORDERS, BuyOrder::fromJson, o -> o.state() == BuyOrder.State.CLOSED, BuyOrder::id, orders, damaged);
		loadFolder(PAYOUTS, Payout::fromJson, p -> false, Payout::id, payouts, damaged);
		return damaged;
	}

	private <T> void loadFolder(String folder, Function<JsonObject, Optional<T>> parse, Predicate<T> finished,
			Function<T, String> id, Map<String, T> into, List<String> damaged) {
		for (String key : store.keys(folder)) {
			Optional<JsonObject> doc = store.read(key);
			if (doc.isPresent() && doc.get().has("paid")) {
				// A payout that was paid out: only its removal was missing.
				store.delete(key);
				continue;
			}
			Optional<T> r = doc.flatMap(parse);
			if (r.isEmpty() || !key.equals(folder + "/" + id.apply(r.get()))) {
				damaged.add(key);
				continue;
			}
			if (finished.test(r.get())) {
				store.delete(key);
				continue;
			}
			into.put(id.apply(r.get()), r.get());
		}
	}

	/** A new ID of 8 hex digits, unused by any lot, order or payout (short enough to type). */
	public String newId() {
		while (true) {
			StringBuilder b = new StringBuilder(8);
			for (int i = 0; i < 8; i++) {
				b.append(HEX[random.nextInt(16)]);
			}
			String id = b.toString();
			if (!lots.containsKey(id) && !orders.containsKey(id) && !payouts.containsKey(id) && store.read(LOTS + "/" + id).isEmpty()
					&& store.read(ORDERS + "/" + id).isEmpty() && store.read(PAYOUTS + "/" + id).isEmpty()) {
				return id;
			}
		}
	}

	// ---------------------------------------------------------------- lots

	public Optional<Lot> lot(String id) {
		return Optional.ofNullable(lots.get(id));
	}

	public Collection<Lot> lots() {
		return List.copyOf(lots.values());
	}

	/** Listings on sale, newest first. */
	public List<Lot> listed() {
		return lots.values().stream().filter(l -> l.state() == Lot.State.LISTED)
				.sorted(Comparator.comparingLong(Lot::createdMillis).reversed()).toList();
	}

	public List<Lot> listedBy(UUID seller) {
		return listed().stream().filter(l -> l.seller().equals(seller)).toList();
	}

	/** What waits in a player's collection box, oldest first. */
	public List<Lot> collectable(UUID holder) {
		return lots.values().stream().filter(l -> l.state().collectable() && holder.equals(l.holder()))
				.sorted(Comparator.comparingLong(Lot::createdMillis)).toList();
	}

	public List<Lot> pendingOf(UUID player) {
		return lots.values().stream().filter(l -> l.state().pending() && l.seller().equals(player)).toList();
	}

	/**
	 * Writes the lot to disk, then makes it current.
	 *
	 * @throws IOException if it could not be written; the current lot is unchanged then
	 */
	public void saveNow(Lot lot) throws IOException {
		store.writeNow(LOTS + "/" + lot.id(), lot.toJson());
		lots.put(lot.id(), lot);
	}

	/** Writes the lot as {@link Lot.State#GONE} (nothing left), then removes it. */
	public void finish(Lot lot) throws IOException {
		store.writeNow(LOTS + "/" + lot.id(), lot.gone().toJson());
		lots.remove(lot.id());
		store.delete(LOTS + "/" + lot.id());
	}

	/** Removes a lot whose items never left the player (a pending lot whose save did not happen). */
	public void discard(Lot lot) {
		lots.remove(lot.id());
		store.delete(LOTS + "/" + lot.id());
	}

	// ---------------------------------------------------------------- orders

	public Optional<BuyOrder> order(String id) {
		return Optional.ofNullable(orders.get(id));
	}

	/** Open buy orders, best price first, then oldest first. */
	public List<BuyOrder> openOrders() {
		return orders.values().stream().filter(o -> o.state() == BuyOrder.State.OPEN)
				.sorted(Comparator.comparingLong(BuyOrder::unitPrice).reversed().thenComparingLong(BuyOrder::createdMillis)).toList();
	}

	public List<BuyOrder> ordersOf(UUID owner) {
		return openOrders().stream().filter(o -> o.owner().equals(owner)).toList();
	}

	public void saveNow(BuyOrder order) throws IOException {
		store.writeNow(ORDERS + "/" + order.id(), order.toJson());
		if (order.state() == BuyOrder.State.CLOSED) {
			orders.remove(order.id());
			store.delete(ORDERS + "/" + order.id());
		} else {
			orders.put(order.id(), order);
		}
	}

	// ---------------------------------------------------------------- payouts

	public List<Payout> payoutsOf(UUID holder) {
		return payouts.values().stream().filter(p -> p.holder().equals(holder)).sorted(Comparator.comparingLong(Payout::createdMillis)).toList();
	}

	public Optional<Payout> payout(String id) {
		return Optional.ofNullable(payouts.get(id));
	}

	public void saveNow(Payout payout) throws IOException {
		store.writeNow(PAYOUTS + "/" + payout.id(), payout.toJson());
		payouts.put(payout.id(), payout);
	}

	/** Marks the payout as paid on disk (before the money is handed out), then removes it. */
	public void finish(Payout payout) throws IOException {
		JsonObject paid = payout.toJson();
		paid.addProperty("paid", true);
		store.writeNow(PAYOUTS + "/" + payout.id(), paid);
		payouts.remove(payout.id());
		store.delete(PAYOUTS + "/" + payout.id());
	}

	// ---------------------------------------------------------------- small documents

	public Optional<JsonObject> read(String key) {
		return store.read(key);
	}

	/** Queued: for documents that move no items or money (price history, bot statistics). */
	public void writeLater(String key, JsonObject doc) {
		store.write(key, doc);
	}
}
