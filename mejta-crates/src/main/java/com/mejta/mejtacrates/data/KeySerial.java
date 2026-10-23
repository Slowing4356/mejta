package com.mejta.mejtacrates.data;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/**
 * The record of one batch of physical keys, {@code serials/<serial>}. Every key item carries its batch's
 * serial; a key is only accepted while fewer keys of its batch were used than were issued. A copied key
 * (from a duplication glitch or another mod) therefore opens nothing once the real ones are used up.
 *
 * @param serial 16 lowercase hex digits
 * @param issued how many keys were made with this serial
 * @param used   how many of them were used to open the crate
 */
public record KeySerial(String serial, String crate, int issued, int used, UUID owner, String ownerName, String issuedBy, long millis) {
	public static final Pattern SERIAL_PATTERN = Pattern.compile("[0-9a-f]{16}");

	public KeySerial {
		if (!isValidSerial(serial)) {
			throw new IllegalArgumentException("Invalid serial " + serial);
		}
		Objects.requireNonNull(crate, "crate");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(ownerName, "ownerName");
		Objects.requireNonNull(issuedBy, "issuedBy");
		if (issued < 0 || used < 0) {
			throw new IllegalArgumentException("negative count");
		}
	}

	public static boolean isValidSerial(String serial) {
		return serial != null && SERIAL_PATTERN.matcher(serial).matches();
	}

	public boolean hasUnused() {
		return used < issued;
	}

	public KeySerial withUsed(int newUsed) {
		return new KeySerial(serial, crate, issued, newUsed, owner, ownerName, issuedBy, millis);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("crate", crate);
		json.addProperty("issued", issued);
		json.addProperty("used", used);
		json.addProperty("owner", owner.toString());
		json.addProperty("owner_name", ownerName);
		json.addProperty("issued_by", issuedBy);
		json.addProperty("millis", millis);
		return json;
	}

	public static KeySerial fromJson(String serial, JsonObject json) {
		return new KeySerial(serial, json.get("crate").getAsString(), json.get("issued").getAsInt(), json.get("used").getAsInt(),
				UUID.fromString(json.get("owner").getAsString()), json.get("owner_name").getAsString(),
				json.has("issued_by") ? json.get("issued_by").getAsString() : "", json.has("millis") ? json.get("millis").getAsLong() : 0);
	}
}
