package com.mejta.mejtacore.api.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * One account in a balance ranking ({@link EconomyService#top}).
 *
 * @param player  the account's player
 * @param name    the player's last known name
 * @param balance in minor units
 * @since API version 6
 */
public record BalanceEntry(UUID player, String name, long balance) {
	public BalanceEntry {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(name, "name");
	}
}
