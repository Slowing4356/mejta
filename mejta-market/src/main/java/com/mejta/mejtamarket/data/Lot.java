package com.mejta.mejtamarket.data;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Some items held by the market: one listing, or items waiting in someone's collection box. The stored
 * record is the only copy of its items; the game never holds them at the same time (see {@link State}).
 *
 * @param item      the item with a count of 1, as written by the game's item codec
 * @param count     how many of it (can be more than a stack)
 * @param price     the whole lot's price in minor units (listings)
 * @param holder    who may collect it ({@link State#SOLD}, {@link State#RETURNED}, {@link State#FILLED})
 * @param before    inventory slots as they were before the items were taken (pending states only), to
 *                  tell after a crash whether the player's save still holds them
 * @param orderId   the buy order a fill belongs to
 * @param payTo     who fills the order, and gets paid
 * @param payAmount what the order pays for the fill, before tax
 */
public record Lot(String id, State state, JsonElement item, String itemId, String itemName, String category, int count, UUID seller,
		String sellerName, long price, long createdMillis, long expiresMillis, UUID holder, String note, UUID buyer, String buyerName,
		long soldMillis, String orderId, UUID payTo, long payAmount, Map<Integer, JsonElement> before) {

	public enum State {
		/** Being listed: on disk before the items leave the inventory; finished or dropped at the seller's next join. */
		PENDING_LIST,
		/** On sale. */
		LISTED,
		/** Bought; waiting in the buyer's collection box. */
		SOLD,
		/** Expired or cancelled; waiting in the seller's collection box. */
		RETURNED,
		/** Being sold into a buy order: like {@link #PENDING_LIST}, finished at the filler's next join. */
		PENDING_FILL,
		/** Delivered by a buy order; waiting in the order owner's collection box. */
		FILLED,
		/** Bought by the market bot (destroyed) or handed out: nothing left. Removed soon after. */
		GONE;

		/** Whether the holder can take the items. */
		public boolean collectable() {
			return this == SOLD || this == RETURNED || this == FILLED;
		}

		public boolean pending() {
			return this == PENDING_LIST || this == PENDING_FILL;
		}
	}

	public Lot {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(item, "item");
		Objects.requireNonNull(itemId, "itemId");
		Objects.requireNonNull(seller, "seller");
		itemName = itemName == null ? itemId : itemName;
		category = category == null ? "OTHER" : category;
		sellerName = sellerName == null ? "?" : sellerName;
		note = note == null ? "" : note;
		before = before == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(before));
		if (count <= 0) {
			throw new IllegalArgumentException("count must be positive");
		}
	}

	/** A new listing, pending until the items have left the seller's inventory. */
	public static Lot pendingListing(String id, JsonElement item, String itemId, String itemName, String category, int count, UUID seller,
			String sellerName, long price, long now, long expires, Map<Integer, JsonElement> before) {
		return new Lot(id, State.PENDING_LIST, item, itemId, itemName, category, count, seller, sellerName, price, now, expires, null, "", null,
				null, 0, null, null, 0, before);
	}

	/** A listing the market bot puts up (its items come from nowhere, so nothing is pending). */
	public static Lot botListing(String id, JsonElement item, String itemId, String itemName, String category, int count, UUID bot,
			String botName, long price, long now, long expires) {
		return new Lot(id, State.LISTED, item, itemId, itemName, category, count, bot, botName, price, now, expires, null, "", null, null, 0,
				null, null, 0, null);
	}

	/** Items being sold into a buy order, pending until they left the filler's inventory. */
	public static Lot pendingFill(String id, JsonElement item, String itemId, String itemName, String category, int count, UUID orderOwner,
			String orderOwnerName, UUID filler, String fillerName, long now, String orderId, long payAmount, Map<Integer, JsonElement> before) {
		return new Lot(id, State.PENDING_FILL, item, itemId, itemName, category, count, filler, fillerName, payAmount, now, now, orderOwner,
				"Buy order filled by " + fillerName, orderOwner, orderOwnerName, now, orderId, filler, payAmount, before);
	}

	/** Items the market bot delivers into a buy order (made from nothing, so nothing is pending). */
	public static Lot botFill(String id, JsonElement item, String itemId, String itemName, String category, int count, UUID orderOwner,
			String orderOwnerName, UUID bot, String botName, long now, String orderId, long payAmount) {
		return new Lot(id, State.FILLED, item, itemId, itemName, category, count, bot, botName, payAmount, now, now, orderOwner,
				"Buy order filled by " + botName, orderOwner, orderOwnerName, now, orderId, bot, payAmount, null);
	}

	public Lot listed() {
		return new Lot(id, State.LISTED, item, itemId, itemName, category, count, seller, sellerName, price, createdMillis, expiresMillis, null,
				"", null, null, 0, orderId, payTo, payAmount, null);
	}

	public Lot sold(UUID buyerId, String name, long now) {
		return new Lot(id, State.SOLD, item, itemId, itemName, category, count, seller, sellerName, price, createdMillis, expiresMillis, buyerId,
				"Bought from " + sellerName, buyerId, name, now, orderId, payTo, payAmount, null);
	}

	/** Back to the seller (expired or cancelled). */
	public Lot returned(String why) {
		return new Lot(id, State.RETURNED, item, itemId, itemName, category, count, seller, sellerName, price, createdMillis, expiresMillis,
				seller, why, null, null, 0, orderId, payTo, payAmount, null);
	}

	public Lot filled() {
		return new Lot(id, State.FILLED, item, itemId, itemName, category, count, seller, sellerName, price, createdMillis, expiresMillis, holder,
				note, buyer, buyerName, soldMillis, orderId, payTo, payAmount, null);
	}

	public Lot gone() {
		return new Lot(id, State.GONE, item, itemId, itemName, category, count, seller, sellerName, price, createdMillis, expiresMillis, holder,
				note, buyer, buyerName, soldMillis, orderId, payTo, payAmount, null);
	}

	/** The same lot with fewer items left (part of it was collected). */
	public Lot withCount(int left) {
		return new Lot(id, state, item, itemId, itemName, category, left, seller, sellerName, price, createdMillis, expiresMillis, holder, note,
				buyer, buyerName, soldMillis, orderId, payTo, payAmount, before);
	}

	public boolean expired(long now) {
		return state == State.LISTED && now >= expiresMillis;
	}

	/** Price per item, rounded down. */
	public long unitPrice() {
		return price / count;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("state", state.name());
		o.add("item", item.deepCopy());
		o.addProperty("item_id", itemId);
		o.addProperty("item_name", itemName);
		o.addProperty("category", category);
		o.addProperty("count", count);
		o.addProperty("seller", seller.toString());
		o.addProperty("seller_name", sellerName);
		o.addProperty("price", price);
		o.addProperty("created", createdMillis);
		o.addProperty("expires", expiresMillis);
		if (holder != null) {
			o.addProperty("holder", holder.toString());
		}
		o.addProperty("note", note);
		if (buyer != null) {
			o.addProperty("buyer", buyer.toString());
			o.addProperty("buyer_name", buyerName);
			o.addProperty("sold", soldMillis);
		}
		if (orderId != null) {
			o.addProperty("order", orderId);
		}
		if (payTo != null) {
			o.addProperty("pay_to", payTo.toString());
			o.addProperty("pay_amount", payAmount);
		}
		if (!before.isEmpty()) {
			JsonObject b = new JsonObject();
			before.forEach((slot, stack) -> b.add(Integer.toString(slot), stack.deepCopy()));
			o.add("before", b);
		}
		return o;
	}

	/** The lot, or empty if the document is damaged. */
	public static Optional<Lot> fromJson(JsonObject o) {
		try {
			Map<Integer, JsonElement> before = new TreeMap<>();
			if (o.has("before")) {
				o.getAsJsonObject("before").entrySet().forEach(e -> before.put(Integer.parseInt(e.getKey()), e.getValue()));
			}
			return Optional.of(new Lot(o.get("id").getAsString(), State.valueOf(o.get("state").getAsString()), o.get("item"),
					o.get("item_id").getAsString(), Json.string(o, "item_name"), Json.string(o, "category"), o.get("count").getAsInt(),
					UUID.fromString(o.get("seller").getAsString()), Json.string(o, "seller_name"), o.get("price").getAsLong(),
					o.get("created").getAsLong(), o.get("expires").getAsLong(), Json.uuid(o, "holder"), Json.string(o, "note"),
					Json.uuid(o, "buyer"), Json.string(o, "buyer_name"), o.has("sold") ? o.get("sold").getAsLong() : 0,
					Json.string(o, "order"), Json.uuid(o, "pay_to"), o.has("pay_amount") ? o.get("pay_amount").getAsLong() : 0, before));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
