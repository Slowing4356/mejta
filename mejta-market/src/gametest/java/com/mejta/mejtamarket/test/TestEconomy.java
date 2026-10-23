package com.mejta.mejtamarket.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.economy.BalanceEntry;
import com.mejta.mejtacore.api.economy.EconomyProvider;
import com.mejta.mejtacore.api.economy.EconomyResult;

/** A tiny in-memory economy (2 decimals) registered as a MejtaCore addon, so the market has money to move in game tests. */
public final class TestEconomy implements MejtaAddon, EconomyProvider {
	static final Map<UUID, Long> BALANCES = new ConcurrentHashMap<>();

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder("markettesteco").name("Market test economy").version("1.0.0")
				.onInitialize(ctx -> MejtaCoreAPI.economy().registerProvider("markettesteco", this)).build());
	}

	@Override
	public int fractionDigits() {
		return 2;
	}

	@Override
	public String currencySingular() {
		return "coin";
	}

	@Override
	public String currencyPlural() {
		return "coins";
	}

	@Override
	public String format(long amount) {
		return String.format("%d.%02d", amount / 100, Math.abs(amount % 100));
	}

	@Override
	public boolean hasAccount(UUID player) {
		return BALANCES.containsKey(player);
	}

	@Override
	public boolean ensureAccount(UUID player, Optional<String> name) {
		BALANCES.putIfAbsent(player, 0L);
		return true;
	}

	@Override
	public long balance(UUID player) {
		return BALANCES.getOrDefault(player, 0L);
	}

	@Override
	public EconomyResult deposit(UUID player, long amount, String source, String reason) {
		return EconomyResult.success(BALANCES.merge(player, amount, Long::sum));
	}

	@Override
	public EconomyResult withdraw(UUID player, long amount, String source, String reason) {
		long have = balance(player);
		if (have < amount) {
			return EconomyResult.failure(EconomyResult.Status.INSUFFICIENT_FUNDS, have, "Not enough coins.");
		}
		BALANCES.put(player, have - amount);
		return EconomyResult.success(have - amount);
	}

	@Override
	public EconomyResult transfer(UUID from, UUID to, long amount, String source, String reason) {
		EconomyResult r = withdraw(from, amount, source, reason);
		if (r.success()) {
			deposit(to, amount, source, reason);
		}
		return r;
	}

	@Override
	public List<BalanceEntry> top(int limit) {
		return List.of();
	}
}
