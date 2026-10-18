package com.mejta.mejtaac.alert;

import java.util.Map;

import com.mejta.mejtaac.AcPermissions;
import com.mejta.mejtaac.Detection;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;

/**
 * Publishes staff alerts through MejtaCore's notification service. MejtaAC never talks to Discord
 * or any other service directly: providers (in-game chat built in; MejtaDiscord later) decide where
 * each channel goes.
 *
 * <p>Channels:
 * <ul>
 * <li>{@code mejtaac.alert}: a player was flagged</li>
 * <li>{@code mejtaac.enforcement}: a player was kicked, or would have been in DRY_RUN</li>
 * </ul>
 * Transmitted fields: player name and UUID, check, reason, violation level, measurements, ping,
 * world and action. No IP addresses or other personal data.
 */
public final class AlertPublisher {
	public static final String CHANNEL_ALERT = "mejtaac.alert";
	public static final String CHANNEL_ENFORCEMENT = "mejtaac.enforcement";

	private final String addonId;

	public AlertPublisher(String addonId) {
		this.addonId = addonId;
	}

	public void alert(Detection d) {
		String more = d.suppressed() > 0 ? " (+" + d.suppressed() + " similar)" : "";
		MejtaCoreAPI.notifications().publish(fields(Notification.builder(addonId, CHANNEL_ALERT), d)
				.severity(Severity.WARNING)
				.title(d.playerName() + " failed " + d.flag().checkId())
				.message(d.flag().reason() + " " + measurements(d.flag().measurements()) + " VL " + format(d.violationLevel()) + more)
				.inGameAudience(AcPermissions.ALERTS)
				.build());
	}

	public void enforcement(Detection d) {
		boolean kicked = d.action() == Action.KICK;
		MejtaCoreAPI.notifications().publish(fields(Notification.builder(addonId, CHANNEL_ENFORCEMENT), d)
				.severity(kicked ? Severity.CRITICAL : Severity.WARNING)
				.title(kicked ? d.playerName() + " was kicked" : d.playerName() + " would be kicked (dry run)")
				.message(d.flag().checkId() + " reached VL " + format(d.violationLevel()))
				.inGameAudience(AcPermissions.ALERTS)
				.build());
	}

	private static Notification.Builder fields(Notification.Builder b, Detection d) {
		b.field("player", d.playerName())
				.field("uuid", d.playerId())
				.field("check", d.flag().checkId())
				.field("reason", d.flag().reason())
				.field("confidence", d.flag().confidence())
				.field("vl", format(d.violationLevel()))
				.field("ping_ms", d.pingMs())
				.field("world", d.world())
				.field("action", d.action());
		d.flag().measurements().forEach(b::field);
		return b;
	}

	static String measurements(Map<String, Object> values) {
		StringBuilder out = new StringBuilder("(");
		values.forEach((k, v) -> {
			if (out.length() > 1) {
				out.append(", ");
			}
			out.append(k).append('=').append(v instanceof Double || v instanceof Float ? format(((Number) v).doubleValue()) : v);
		});
		return out.append(')').toString();
	}

	static String format(double value) {
		return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : String.valueOf(value);
	}
}
