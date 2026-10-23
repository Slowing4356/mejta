package com.mejta.mejtacore.api.rank;

import java.util.UUID;

/**
 * Told about every rank change, on the server thread. Register with {@link RankService#addRankListener}.
 *
 * @since API version 8
 */
@FunctionalInterface
public interface RankListener {
	/** {@code player} may be offline. */
	void onRankChange(UUID player, Rank before, Rank after);
}
