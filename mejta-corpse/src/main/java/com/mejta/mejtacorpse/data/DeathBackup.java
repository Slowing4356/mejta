package com.mejta.mejtacorpse.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A copy of the items a player had when they died, kept after their corpse is gone, so staff can give
 * them back. Stored in {@code backups/<owner uuid>/<created millis>-<corpse id>}.
 *
 * <p>Unlike a corpse, a backup is not where the items live: it is a snapshot taken when the corpse became
 * active. Its {@link #history()} says what then happened to the corpse (who took items, whether it
 * expired), and whether the backup was already restored. A backup can be restored only once.
 *
 * @param id      the corpse's ID
 * @param items   item JSON by the owner's inventory slot, as in {@link CorpseRecord#items()}
 * @param history what happened to the corpse and the backup, oldest first
 */
public record DeathBackup(String id, UUID owner, String ownerName, long createdMillis, String dimension, double x, double y, double z,
		Map<Integer, JsonElement> items, List<Event> history) {
	public enum Kind {
		/** The owner took items from the corpse. */
		TAKEN_BY_OWNER,
		/** Someone else took items from the corpse. */
		TAKEN_BY_OTHER,
		/** The corpse expired; {@link Event#detail()} says whether its items dropped or were deleted. */
		EXPIRED,
		/** Staff gave the items back; the backup cannot be restored again. */
		RESTORED
	}

	/**
	 * @param actor  the player's name, or empty for the server
	 * @param count  items involved
	 * @param detail extra text, or empty
	 */
	public record Event(long millis, Kind kind, String actor, int count, String detail) {
	}

	public DeathBackup {
		items = Collections.unmodifiableMap(new TreeMap<>(items));
		history = List.copyOf(history);
	}

	/** The backup of a corpse that has just become active. */
	public static DeathBackup of(CorpseRecord corpse) {
		return new DeathBackup(corpse.id(), corpse.owner(), corpse.ownerName(), corpse.createdMillis(), corpse.dimension(), corpse.x(),
				corpse.y(), corpse.z(), corpse.items(), List.of());
	}

	public DeathBackup withEvent(Event event) {
		List<Event> more = new ArrayList<>(history);
		more.add(event);
		return new DeathBackup(id, owner, ownerName, createdMillis, dimension, x, y, z, items, more);
	}

	/** The restore, if this backup was restored. */
	public Optional<Event> restored() {
		return history.stream().filter(e -> e.kind() == Kind.RESTORED).findFirst();
	}

	/** Items the owner took back from the corpse themselves (restoring gives those a second time). */
	public int takenByOwner() {
		return history.stream().filter(e -> e.kind() == Kind.TAKEN_BY_OWNER).mapToInt(Event::count).sum();
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("id", id);
		o.addProperty("owner", owner.toString());
		o.addProperty("owner_name", ownerName);
		o.addProperty("created", createdMillis);
		o.addProperty("dimension", dimension);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		JsonObject list = new JsonObject();
		items.forEach((slot, item) -> list.add(String.valueOf(slot), item.deepCopy()));
		o.add("items", list);
		JsonArray events = new JsonArray();
		for (Event e : history) {
			JsonObject j = new JsonObject();
			j.addProperty("time", e.millis());
			j.addProperty("kind", e.kind().name());
			j.addProperty("actor", e.actor());
			j.addProperty("count", e.count());
			j.addProperty("detail", e.detail());
			events.add(j);
		}
		o.add("history", events);
		return o;
	}

	/** The stored backup, or empty if it is damaged. Bad item slots and unknown events are skipped. */
	public static Optional<DeathBackup> fromJson(JsonObject o) {
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
			List<Event> history = new ArrayList<>();
			if (o.has("history") && o.get("history").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("history")) {
					try {
						JsonObject j = e.getAsJsonObject();
						history.add(new Event(j.get("time").getAsLong(), Kind.valueOf(j.get("kind").getAsString()),
								j.has("actor") ? j.get("actor").getAsString() : "", j.has("count") ? j.get("count").getAsInt() : 0,
								j.has("detail") ? j.get("detail").getAsString() : ""));
					} catch (RuntimeException ignored) {
						// unknown or damaged event: skipped
					}
				}
			}
			return Optional.of(new DeathBackup(o.get("id").getAsString(), UUID.fromString(o.get("owner").getAsString()),
					o.get("owner_name").getAsString(), o.get("created").getAsLong(), o.get("dimension").getAsString(),
					o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), items, history));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
