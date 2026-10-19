package com.mejta.mejtaqol.home;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mejta.mejtacore.api.storage.DataStore;

/**
 * Players' home books ({@code homes/<uuid>} in the data store). Online players' books are cached; any
 * player's book (also an offline one's) can be read and changed by UUID. Server thread only.
 */
public final class Homes {
	private static final String FOLDER = "homes";

	private final DataStore store;
	private final Map<UUID, HomeBook> cache = new ConcurrentHashMap<>();

	public Homes(DataStore store) {
		this.store = store;
	}

	public HomeBook book(UUID player) {
		return cache.computeIfAbsent(player, id -> store.read(DataStore.playerKey(FOLDER, id)).map(HomeBook::fromJson).orElseGet(HomeBook::new));
	}

	/** Writes the player's book (call after changing it). */
	public void save(UUID player) {
		HomeBook book = cache.get(player);
		if (book == null) {
			return;
		}
		if (book.size() == 0) {
			store.delete(DataStore.playerKey(FOLDER, player));
		} else {
			store.write(DataStore.playerKey(FOLDER, player), book.toJson());
		}
	}

	/** Drops a player's book from the cache when they leave (it is already saved). */
	public void forget(UUID player) {
		cache.remove(player);
	}

	public void clear() {
		cache.clear();
	}
}
