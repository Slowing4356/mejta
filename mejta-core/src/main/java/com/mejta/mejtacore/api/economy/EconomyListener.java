package com.mejta.mejtacore.api.economy;

import java.util.UUID;

/**
 * Receives every successful balance change made through {@link EconomyService}, on the server thread,
 * after it was made. A transfer is reported as one call per account. Useful for sidebars and
 * leaderboards; never change balances from inside a listener.
 *
 * @since API version 6
 */
@FunctionalInterface
public interface EconomyListener {
	/**
	 * @param player  whose balance changed
	 * @param oldBalance before, in minor units
	 * @param newBalance after, in minor units
	 * @param source  the addon that asked for the change, e.g. {@code mejtaauction}
	 * @param reason  the reason it gave, e.g. {@code "auction sale"}
	 */
	void onBalanceChange(UUID player, long oldBalance, long newBalance, String source, String reason);
}
