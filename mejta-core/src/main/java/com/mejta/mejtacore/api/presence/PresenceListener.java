package com.mejta.mejtacore.api.presence;

import java.util.UUID;

/**
 * Receives public joins and leaves: what regular players are shown, not what really happened.
 * A hidden player's real join is not reported; a staff member's announced fake leave is, with
 * {@code real = false}. Called on the server thread.
 *
 * @since API version 4
 */
@FunctionalInterface
public interface PresenceListener {
	enum Kind {
		JOIN,
		LEAVE
	}

	/**
	 * @param real {@code false} for a join or leave announced on purpose by staff (fake join or quit)
	 */
	void onPresence(UUID player, String name, Kind kind, boolean real);
}
