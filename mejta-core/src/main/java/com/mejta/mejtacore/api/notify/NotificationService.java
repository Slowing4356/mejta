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
	 * delivery problems. Each provider has its own queue (since API 4), so a slow provider never delays
	 * the others; if a provider's queue is full the notification is dropped for it and counted.
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

	/**
	 * Mutes or unmutes a channel for one player in the in-game provider. Saved across restarts since
	 * MejtaCore 1.3.0 (API 4); before that, mutes reset on restart.
	 */
	void setMuted(UUID player, String channel, boolean muted);

	boolean isMuted(UUID player, String channel);

	/** Notifications dropped because the queue was full since startup. */
	long droppedCount();
}
