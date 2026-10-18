package com.mejta.mejtacore.api.notify;

/**
 * Delivers {@link Notification}s somewhere: in-game chat, Discord, a webhook...
 *
 * <p>{@link #deliver} is called on MejtaCore's notification thread, never on the server thread, so
 * it may block on network I/O. Exceptions are caught and logged (rate-limited); they never affect
 * the publisher or other providers. A provider that needs the server thread must hand work over to
 * it itself (e.g. {@code server.execute(...)}).
 *
 * <p>Providers must only send data to external services the server owner explicitly configured.
 */
public interface NotificationProvider {
	/** Unique provider ID, e.g. {@code mejtadiscord}. */
	String id();

	/** Fast filter called before {@link #deliver}; e.g. only channels the owner routed somewhere. */
	default boolean accepts(Notification notification) {
		return true;
	}

	void deliver(Notification notification) throws Exception;
}
