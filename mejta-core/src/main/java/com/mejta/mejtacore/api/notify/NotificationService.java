package com.mejta.mejtacore.api.notify;

import java.util.List;
import java.util.UUID;

/**
 * Routes notifications from addons to providers. Obtain it with {@code MejtaCoreAPI.notifications()}.
 *
 * <p>Publishers never depend on a provider: if none is installed, notifications are simply not
 * delivered anywhere except the built-in in-game provider.
 */
public interface NotificationService {
	/**
	 * Queues a notification for delivery and returns immediately. Never blocks and never throws for
	 * delivery problems; if the queue is full the notification is dropped and the drop is counted.
	 */
	void publish(Notification notification);

	/**
	 * Adds a provider. May be called at any time.
	 *
	 * @return {@code false} if a provider with the same ID is already registered
	 */
	boolean registerProvider(NotificationProvider provider);

	/** IDs of registered providers, including the built-in {@code ingame} provider. */
	List<String> providerIds();

	/** Mutes or unmutes a channel for one player in the in-game provider. Not persisted across restarts. */
	void setMuted(UUID player, String channel, boolean muted);

	boolean isMuted(UUID player, String channel);

	/** Notifications dropped because the queue was full since startup. */
	long droppedCount();
}
