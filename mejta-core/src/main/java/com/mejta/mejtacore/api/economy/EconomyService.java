package com.mejta.mejtacore.api.economy;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One shared economy for every addon, like Vault. Obtain it with {@code MejtaCoreAPI.economy()}.
 *
 * <p>MejtaCore only defines the API: the accounts are kept by an economy addon (MejtaEconomy) that
 * registers an {@link EconomyProvider}. Shops, crates and auction houses call this service and never
 * depend on the economy addon. Without a provider every change returns
 * {@link EconomyResult.Status#NO_PROVIDER}; check {@link #isAvailable()} to hide money features then.
 *
 * <p><b>Amounts</b> are {@code long}s in minor units: with {@link #fractionDigits()} = 2,
 * {@code 1050} means {@code 10.50}. Use {@link #parse} for player input and {@link #format} for output.
 * They are never fractional, so prices add up exactly.
 *
 * <p><b>Source and reason.</b> Every change names the addon that asks for it ({@code source}, its
 * addon ID) and a short reason ({@code "crate reward"}). The provider logs them for staff.
 *
 * <p>Call these methods on the server thread.
 *
 * <pre>{@code
 * EconomyService eco = MejtaCoreAPI.economy();
 * EconomyResult paid = eco.transfer(buyer, seller, price, "mejtaauction", "bought " + item);
 * if (!paid.success()) {
 *     player.sendSystemMessage(Component.literal(paid.message()));
 * }
 * }</pre>
 *
 * @since API version 6
 */
public interface EconomyService {
	/**
	 * Registers the economy. Only one provider can be active; a second registration is refused.
	 *
	 * @throws IllegalStateException if a provider is already registered
	 */
	void registerProvider(String addonId, EconomyProvider provider);

	/** Whether a provider is registered and its addon is active. */
	boolean isAvailable();

	/** The ID of the addon that provides the economy. */
	Optional<String> providerId();

	/** Digits after the decimal point; 2 without a provider. */
	int fractionDigits();

	/** {@code currencySingular} for an amount of exactly one unit, else {@code currencyPlural}. */
	String currencyName(long amount);

	/** The amount for players, e.g. {@code "$12.50"}; plain {@code "12.50"} without a provider. */
	String format(long amount);

	/**
	 * Reads an amount typed by a player, e.g. {@code "12.5"}, {@code "1,000"} or {@code "2k"} (k = thousand,
	 * m = million). Empty if it is not a positive amount or has more decimals than {@link #fractionDigits()}.
	 */
	OptionalLong parse(String text);

	boolean hasAccount(UUID player);

	/** Creates the player's account if needed. @return whether the player has one now */
	boolean ensureAccount(UUID player, Optional<String> name);

	/** In minor units; {@code 0} without an account or a provider. */
	long balance(UUID player);

	/** Whether the player has at least {@code amount}. */
	boolean has(UUID player, long amount);

	/**
	 * Adds money. Works for offline players: an account is created if the player has none (e.g. a
	 * seller who is offline when the item sells).
	 */
	EconomyResult deposit(UUID player, long amount, String source, String reason);

	/** Takes money if the player has enough, else {@link EconomyResult.Status#INSUFFICIENT_FUNDS}. */
	EconomyResult withdraw(UUID player, long amount, String source, String reason);

	/** Moves money between two players, both or neither (e.g. a purchase). */
	EconomyResult transfer(UUID from, UUID to, long amount, String source, String reason);

	/** The richest accounts, best first; empty without a provider. */
	List<BalanceEntry> top(int limit);

	/** Receives every successful change from now on. */
	void addListener(EconomyListener listener);
}
