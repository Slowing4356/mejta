package com.mejta.mejtautils.vanish;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtautils.UtilsSettings;

/** One staff member's vanish state and personal settings, stored as {@code vanish/<uuid>}. */
public final class VanishState {
	public boolean vanished;
	public boolean itemPickup;
	public boolean mobTargeting;
	public boolean nightVision;
	public boolean silentContainers;
	public double flightSpeed;
	/** The player used /vanish fakequit and has not fake-joined since: they appear offline. */
	public boolean fakeOffline;
	/** Whether the player could fly before vanishing (restored on unvanish). */
	public boolean hadFlight;

	static VanishState defaults(ConfigHandle config) {
		VanishState s = new VanishState();
		s.itemPickup = config.get(UtilsSettings.VANISH_PICKUP);
		s.mobTargeting = config.get(UtilsSettings.VANISH_MOB_TARGETING);
		s.nightVision = config.get(UtilsSettings.VANISH_NIGHT_VISION);
		s.silentContainers = config.get(UtilsSettings.VANISH_SILENT_CONTAINERS);
		s.flightSpeed = config.get(UtilsSettings.VANISH_FLIGHT_SPEED);
		return s;
	}

	JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("vanished", vanished);
		o.addProperty("item_pickup", itemPickup);
		o.addProperty("mob_targeting", mobTargeting);
		o.addProperty("night_vision", nightVision);
		o.addProperty("silent_containers", silentContainers);
		o.addProperty("flight_speed", flightSpeed);
		o.addProperty("fake_offline", fakeOffline);
		o.addProperty("had_flight", hadFlight);
		return o;
	}

	static VanishState fromJson(JsonObject o, ConfigHandle config) {
		VanishState s = defaults(config);
		s.vanished = bool(o, "vanished", false);
		s.itemPickup = bool(o, "item_pickup", s.itemPickup);
		s.mobTargeting = bool(o, "mob_targeting", s.mobTargeting);
		s.nightVision = bool(o, "night_vision", s.nightVision);
		s.silentContainers = bool(o, "silent_containers", s.silentContainers);
		if (o.has("flight_speed") && o.get("flight_speed").isJsonPrimitive()) {
			double speed = o.get("flight_speed").getAsDouble();
			s.flightSpeed = Double.isFinite(speed) ? Math.clamp(speed, 1.0, 4.0) : 1.0;
		}
		s.fakeOffline = bool(o, "fake_offline", false);
		s.hadFlight = bool(o, "had_flight", false);
		return s;
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
	}
}
