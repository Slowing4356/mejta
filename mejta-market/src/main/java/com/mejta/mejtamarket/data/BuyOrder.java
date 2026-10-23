package com.mejta.mejtamarket.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A buy order: its owner wants {@code amount} of an item at {@code unitPrice} each, and the money for the
 * rest ({@code escrow}) was taken when it was placed. Anyone may sell matching items into it. Only items
 * with no extra data (no enchantments, names, damage) match.
 *
 * @param fills the fills already counted (lot IDs), so finishing a fill twice after a crash counts it once
 */
public record BuyOrder(String id, State state, UUID owner, String ownerName, String itemId, String itemName, String category, int amount,
		int filled, long unitPrice, long escrow, long createdMillis, long expiresMillis, List<String> fills, String closeReason) {

	public enum State {
		OPEN,
		/** Filled, cancelled or expired; the unspent money went back. Removed soon after. */
		CLOSED
	}

	public BuyOrder {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(itemId, "itemId");
		ownerName = ownerName == null ? "?" : ownerName;
		itemName = itemName == null ? itemId : itemName;
		category = category == null ? "OTHER" : category;
		fills = fills == null ? List.of() : List.copyOf(fills);
		closeReason = closeReason == null ? "" : closeReason;
		if (amount <= 0 || filled < 0 || filled > amount || unitPrice <= 0 || escrow < 0) {
			throw new IllegalArgumentException("invalid order " + id);
		}
	}

	public static BuyOrder open(String id, UUID owner, String ownerName, String itemId, String itemName, String category, int amount,
			long unitPrice, long now, long expires) {
		return new BuyOrder(id, State.OPEN, owner, ownerName, itemId, itemName, category, amount, 0, unitPrice, unitPrice * amount, now,
				expires, List.of(), "");
	}

	public int remaining() {
		return amount - filled;
	}

	public boolean expired(long now) {
		return state == State.OPEN && now >= expiresMillis;
	}

	/** {@code count} more delivered by fill {@code lotId}, paid from the escrow. */
	public BuyOrder withFill(String lotId, int count) {
		if (count <= 0 || count > remaining()) {
			throw new IllegalArgumentException("fill of " + count + " does not fit order " + id);
		}
		List<String> f = new ArrayList<>(fills);
		f.add(lotId);
		return new BuyOrder(id, state, owner, ownerName, itemId, itemName, category, amount, filled + count, unitPrice,
				escrow - unitPrice * count, createdMillis, expiresMillis, f, closeReason);
	}

	/** Closed; the escrow ({@link #escrow()} before this call) is refunded by the caller. */
	public BuyOrder closed(String reason) {
		return new BuyOrder(id, State.CLOSED, owner, ownerName, itemId, itemName, category, amount, filled, unitPrice, 0, createdMillis,
				expiresMillis, fills, reason);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("state", state.name());
		o.addProperty("owner", owner.toString());
		o.addProperty("owner_name", ownerName);
		o.addProperty("item_id", itemId);
		o.addProperty("item_name", itemName);
		o.addProperty("category", category);
		o.addProperty("amount", amount);
		o.addProperty("filled", filled);
		o.addProperty("unit_price", unitPrice);
		o.addProperty("escrow", escrow);
		o.addProperty("created", createdMillis);
		o.addProperty("expires", expiresMillis);
		JsonArray f = new JsonArray();
		fills.forEach(f::add);
		o.add("fills", f);
		o.addProperty("close_reason", closeReason);
		return o;
	}

	public static Optional<BuyOrder> fromJson(JsonObject o) {
		try {
			List<String> fills = new ArrayList<>();
			if (o.has("fills")) {
				for (JsonElement e : o.getAsJsonArray("fills")) {
					fills.add(e.getAsString());
				}
			}
			return Optional.of(new BuyOrder(o.get("id").getAsString(), State.valueOf(o.get("state").getAsString()),
					UUID.fromString(o.get("owner").getAsString()), Json.string(o, "owner_name"), o.get("item_id").getAsString(),
					Json.string(o, "item_name"), Json.string(o, "category"), o.get("amount").getAsInt(), o.get("filled").getAsInt(),
					o.get("unit_price").getAsLong(), o.get("escrow").getAsLong(), o.get("created").getAsLong(), o.get("expires").getAsLong(), fills,
					Json.string(o, "close_reason")));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
