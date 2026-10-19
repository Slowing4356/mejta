package com.mejta.mejtaqol.chat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each player's last private-message partner, for {@code /r}. Thread-safe. */
public final class Replies {
	/**
	 * @param partner  who {@code /r} answers
	 * @param incoming whether the partner wrote last (they messaged this player), rather than this player
	 *                 messaging them
	 */
	public record Partner(UUID partner, boolean incoming) {
	}

	private final Map<UUID, Partner> last = new ConcurrentHashMap<>();

	/** {@code sender} sent a private message to {@code target}. */
	public void record(UUID sender, UUID target) {
		if (sender.equals(target)) {
			return;
		}
		last.put(sender, new Partner(target, false));
		last.put(target, new Partner(sender, true));
	}

	public Optional<Partner> partner(UUID player) {
		return Optional.ofNullable(last.get(player));
	}

	public void forget(UUID player) {
		last.remove(player);
	}

	public void clear() {
		last.clear();
	}
}
