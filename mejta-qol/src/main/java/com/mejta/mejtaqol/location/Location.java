package com.mejta.mejtaqol.location;

import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A saved position: dimension ID ({@code minecraft:overworld}), coordinates and rotation. Stored as
 * JSON in homes, warps, the spawn point and {@code /back}.
 */
public record Location(String dimension, double x, double y, double z, float yaw, float pitch) {
	public Location {
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("dimension is required");
		}
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			throw new IllegalArgumentException("coordinates must be finite");
		}
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("dimension", dimension);
		o.addProperty("x", x);
		o.addProperty("y", y);
		o.addProperty("z", z);
		o.addProperty("yaw", yaw);
		o.addProperty("pitch", pitch);
		return o;
	}

	/** The location in {@code json}, or empty if it is missing or malformed. */
	public static Optional<Location> fromJson(JsonElement json) {
		if (json == null || !json.isJsonObject()) {
			return Optional.empty();
		}
		JsonObject o = json.getAsJsonObject();
		try {
			return Optional.of(new Location(o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
					o.get("z").getAsDouble(), o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("pitch") ? o.get("pitch").getAsFloat() : 0));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	/** The dimension without the {@code minecraft:} namespace, e.g. {@code the_nether}. */
	public String shortDimension() {
		return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
	}

	/** Block coordinates and dimension, e.g. {@code 12, 64, -30 (overworld)}. */
	public String describe() {
		return String.format(Locale.ROOT, "%d, %d, %d (%s)", (long) Math.floor(x), (long) Math.floor(y), (long) Math.floor(z), shortDimension());
	}

	/** Squared distance, or {@code +Infinity} in another dimension. */
	public double distanceSquared(Location other) {
		if (!dimension.equals(other.dimension)) {
			return Double.POSITIVE_INFINITY;
		}
		double dx = x - other.x;
		double dy = y - other.y;
		double dz = z - other.z;
		return dx * dx + dy * dy + dz * dz;
	}
}
