package com.mejta.mejtacore.api.economy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the accounts behind {@link EconomyService}. An economy addon (MejtaEconomy) registers one with
 * {@code MejtaCoreAPI.economy().registerProvider(...)}; other addons never call a provider directly.
 *
 * <p>Amounts are {@code long}s in minor units: with {@link #fractionDigits()} = 2, {@code 1050} is
 * {@code 10.50}. MejtaCore checks that amounts are positive before calling the change methods, catches
 * exceptions (turning them into {@link EconomyResult.Status#FAILED}) and informs listeners of every
 * success. Each change must be all or nothing, and a transfer must never create or lose money.
 *
 * <p>Methods are called on the server thread.
 *
 * @since API version 6
 */
public interface EconomyProvider {
	/** Digits after the decimal point, 0 to 4. */
	int fractionDigits();

	/** E.g. {@code "coin"}. */
	String currencySingular();

	/** E.g. {@code "coins"}. */
	String currencyPlural();

	/** E.g. {@code "$12.50"} or {@code "12.50 coins"}. */
	String format(long amount);

	/** Whether the player has an account (has joined, or was given one). */
	boolean hasAccount(UUID player);

	/**
	 * Makes sure the player has an account, e.g. before paying an offline seller.
	 *
	 * @param name the player's name, if known
	 * @return whether the player has an account now
	 */
	boolean ensureAccount(UUID player, Optional<String> name);

	/** The balance in minor units; {@code 0} without an account. */
	long balance(UUID player);

	/** Adds money, creating the account if needed. */
	EconomyResult deposit(UUID player, long amount, String source, String reason);

	EconomyResult withdraw(UUID player, long amount, String source, String reason);

	/** Moves money from one account to the other, both or neither. */
	EconomyResult transfer(UUID from, UUID to, long amount, String source, String reason);

	/** The richest accounts, best first, at most {@code limit}. */
	List<BalanceEntry> top(int limit);
}
