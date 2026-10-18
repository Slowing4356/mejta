package com.mejta.mejtaac.record;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import com.mejta.mejtaac.Detection;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;

/**
 * Persistent records in {@code mejta-data/mejtaac/}, through MejtaCore's size-bounded record logs:
 * <ul>
 * <li>{@code violations.jsonl}: flags (rate-limited per player and check)</li>
 * <li>{@code enforcement.jsonl}: audit trail of kicks and dry-run "would kick" decisions</li>
 * </ul>
 */
public final class AcRecords {
	private final RecordLog violations;
	private final RecordLog enforcement;

	public AcRecords(String addonId, RecordLogOptions options) {
		this.violations = MejtaCoreAPI.recordLog(addonId, "violations", options);
		this.enforcement = MejtaCoreAPI.recordLog(addonId, "enforcement", options);
	}

	public void violation(Detection d) {
		JsonObject record = base("violation", d);
		record.addProperty("confidence", d.flag().confidence().name());
		record.addProperty("vl_added", d.flag().vlIncrease());
		JsonObject measurements = new JsonObject();
		for (Map.Entry<String, Object> m : d.flag().measurements().entrySet()) {
			if (m.getValue() instanceof Number n) {
				// Gson cannot write NaN/Infinity as numbers; keep them readable as strings.
				double v = n.doubleValue();
				if (Double.isFinite(v)) {
					measurements.addProperty(m.getKey(), n);
				} else {
					measurements.addProperty(m.getKey(), String.valueOf(v));
				}
			} else {
				measurements.addProperty(m.getKey(), String.valueOf(m.getValue()));
			}
		}
		record.add("measurements", measurements);
		record.addProperty("ping_ms", d.pingMs());
		record.addProperty("world", d.world());
		record.addProperty("suppressed", d.suppressed());
		violations.append(record);
	}

	public void enforcement(Detection d, String mode) {
		JsonObject record = base("enforcement", d);
		record.addProperty("mode", mode);
		enforcement.append(record);
	}

	/** Newest first; matches the player name case-insensitively, or every record if {@code playerName} is null. */
	public CompletableFuture<List<JsonObject>> recentViolations(String playerName, int limit) {
		return violations.readRecent(limit, r -> playerName == null
				|| (r.has("player") && r.get("player").getAsString().equalsIgnoreCase(playerName)));
	}

	public RecordLog violationLog() {
		return violations;
	}

	public RecordLog enforcementLog() {
		return enforcement;
	}

	private static JsonObject base(String type, Detection d) {
		JsonObject record = new JsonObject();
		record.addProperty("type", type);
		record.addProperty("player", d.playerName());
		record.addProperty("uuid", d.playerId().toString());
		record.addProperty("check", d.flag().checkId());
		record.addProperty("reason", d.flag().reason());
		record.addProperty("vl", round(d.violationLevel()));
		record.addProperty("action", d.action().name());
		return record;
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
