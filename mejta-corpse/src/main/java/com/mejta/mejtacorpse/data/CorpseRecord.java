package com.mejta.mejtacorpse.data;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One corpse, as stored in {@code corpses/<id>}. The stored record is the only place the corpse's items
 * live; the entities in the world only show where it is.
 *
 * @param state      {@link State#PENDING} while the death is being written, {@link State#ACTIVE} afterwards
 * @param items      item JSON by the owner's inventory slot (0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand)
 * @param remote     the owner may open it from anywhere (they died in the void, where nobody can reach it)
 * @param body       UUID of the lying mannequin, if spawned
 * @param hitbox     UUID of the interaction entity that receives clicks, if spawned
 * @param skin       the owner's signed skin texture at death ({@code value} and {@code signature}), if any
 */
public record CorpseRecord(String id, UUID owner, String ownerName, long createdMillis, State state, String dimension, double x,
		double y, double z, float yaw, boolean remote, Map<Integer, JsonElement> items, UUID body, UUID hitbox, Skin skin) {
	/** A skin texture property, as the game's login server signed it. */
	public record Skin(String value, String signature) {
	}

	public enum State {
		PENDING,
		ACTIVE
	}

	public CorpseRecord {
		items = Collections.unmodifiableMap(new TreeMap<>(items));
	}

	public static CorpseRecord pending(String id, UUID owner, String ownerName, long createdMillis, String dimension, double x, double y,
			double z, float yaw, boolean remote, Map<Integer, JsonElement> items, Skin skin) {
		return new CorpseRecord(id, owner, ownerName, createdMillis, State.PENDING, dimension, x, y, z, yaw, remote, items, null, null, skin);
	}

	public CorpseRecord withState(State newState) {
		return new CorpseRecord(id, owner, ownerName, createdMillis, newState, dimension, x, y, z, yaw, remote, items, body, hitbox, skin);
	}

	public CorpseRecord withItems(Map<Integer, JsonElement> newItems) {
		return new CorpseRecord(id, owner, ownerName, createdMillis, state, dimension, x, y, z, yaw, remote, newItems, body, hitbox, skin);
	}

	public CorpseRecord withEntities(UUID newBody, UUID newHitbox) {
		return new CorpseRecord(id, owner, ownerName, createdMillis, state, dimension, x, y, z, yaw, remote, items, newBody, newHitbox, skin);
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	/** Whether {@code entity} is one of this corpse's two entities. */
	public boolean owns(UUID entity) {
		return entity.equals(body) || entity.equals(hitbox);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("id", id);
		o.addProperty("owner", owner.toString());
		o.addProperty("owner_name", ownerName);
		o.addProperty("created", createdMillis);
		o.addProperty("state", state.name());
		o.addProperty("dimension", dimension);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		o.addProperty("yaw", yaw);
		o.addProperty("remote", remote);
		JsonObject list = new JsonObject();
		items.forEach((slot, item) -> list.add(String.valueOf(slot), item.deepCopy()));
		o.add("items", list);
		if (body != null) {
			o.addProperty("body", body.toString());
		}
		if (hitbox != null) {
			o.addProperty("hitbox", hitbox.toString());
		}
		if (skin != null) {
			JsonObject s = new JsonObject();
			s.addProperty("value", skin.value());
			if (skin.signature() != null) {
				s.addProperty("signature", skin.signature());
			}
			o.add("skin", s);
		}
		return o;
	}

	private static Skin skin(JsonObject o) {
		if (!o.has("skin") || !o.get("skin").isJsonObject()) {
			return null;
		}
		JsonObject s = o.getAsJsonObject("skin");
		return new Skin(s.get("value").getAsString(), s.has("signature") ? s.get("signature").getAsString() : null);
	}

	/** The stored record, or empty if it is damaged. Item entries with a bad slot number are skipped. */
	public static Optional<CorpseRecord> fromJson(JsonObject o) {
		try {
			Map<Integer, JsonElement> items = new TreeMap<>();
			if (o.has("items") && o.get("items").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("items").entrySet()) {
					try {
						int slot = Integer.parseInt(e.getKey());
						if (slot >= 0 && slot < SlotLayout.MAX_INVENTORY_SLOTS) {
							items.put(slot, e.getValue().deepCopy());
						}
					} catch (NumberFormatException ignored) {
						// not a slot number: skipped
					}
				}
			}
			return Optional.of(new CorpseRecord(o.get("id").getAsString(), UUID.fromString(o.get("owner").getAsString()),
					o.get("owner_name").getAsString(), o.get("created").getAsLong(), State.valueOf(o.get("state").getAsString()),
					o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
					o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("remote") && o.get("remote").getAsBoolean(), items,
					o.has("body") ? UUID.fromString(o.get("body").getAsString()) : null,
					o.has("hitbox") ? UUID.fromString(o.get("hitbox").getAsString()) : null, skin(o)));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
