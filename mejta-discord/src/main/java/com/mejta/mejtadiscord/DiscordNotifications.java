package com.mejta.mejtadiscord;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtadiscord.net.Bridge;
import com.mejta.mejtadiscord.text.Embeds;

/**
 * Sends MejtaCore notifications (MejtaAC's alerts and kicks, and whatever else {@code notify.routing}
 * selects) to Discord webhooks as embeds. Delivery only queues: MejtaCore's notification thread never
 * waits for Discord. MejtaDiscord's own channels are never sent, so a failing webhook cannot report
 * itself in a loop.
 */
final class DiscordNotifications implements NotificationProvider {
	private final Set<String> warnedTargets = ConcurrentHashMap.newKeySet();

	@Override
	public String id() {
		return MejtaDiscord.ID;
	}

	@Override
	public boolean accepts(Notification notification) {
		MejtaDiscord.Snapshot s = MejtaDiscord.snapshot();
		return s != null && s.enabled() && !notification.channel().startsWith(MejtaDiscord.ID + ".")
				&& notification.severity().ordinal() >= s.minSeverity().ordinal() && s.routing().route(notification.channel()).isPresent();
	}

	@Override
	public void deliver(Notification notification) {
		MejtaDiscord.Snapshot s = MejtaDiscord.snapshot();
		Bridge bridge = MejtaDiscord.bridge();
		if (s == null || bridge == null) {
			return;
		}
		Optional<String> target = s.routing().route(notification.channel());
		if (target.isEmpty()) {
			return;
		}
		var payload = Embeds.payload(s.notifyUsername(), s.notifyAvatar(), notification.title(), notification.message(),
				notification.severity().name(), notification.fields(), notification.channel(), notification.timestamp());
		boolean important = notification.severity() != Severity.INFO;
		if (!bridge.send(target.get(), payload, important) && !bridge.hasWebhook(target.get()) && warnedTargets.add(target.get())) {
			MejtaDiscord.log().warn("notify.routing sends '{}' to webhook '{}', but webhook.{}.url is not set; such notifications are not sent",
					notification.channel(), target.get(), target.get());
		}
	}

	/** Settings changed: warn again about unset webhooks. */
	void settingsChanged() {
		warnedTargets.clear();
	}
}
