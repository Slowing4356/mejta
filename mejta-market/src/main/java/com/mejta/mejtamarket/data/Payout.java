package com.mejta.mejtamarket.data;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * Money the market owes a player but could not pay at once (e.g. their balance would go over the
 * economy's limit). It waits in their collection box.
 */
public record Payout(String id, UUID holder, long amount, String reason, long createdMillis) {
	public Payout {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(holder, "holder");
		reason = reason == null ? "" : reason;
		if (amount <= 0) {
			throw new IllegalArgumentException("amount must be positive");
		}
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("holder", holder.toString());
		o.addProperty("amount", amount);
		o.addProperty("reason", reason);
		o.addProperty("created", createdMillis);
		return o;
	}

	public static Optional<Payout> fromJson(JsonObject o) {
		try {
			return Optional.of(new Payout(o.get("id").getAsString(), UUID.fromString(o.get("holder").getAsString()), o.get("amount").getAsLong(),
					Json.string(o, "reason"), o.get("created").getAsLong()));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
