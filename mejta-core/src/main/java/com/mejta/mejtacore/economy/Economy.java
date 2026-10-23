package com.mejta.mejtacore.economy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.economy.BalanceEntry;
import com.mejta.mejtacore.api.economy.EconomyListener;
import com.mejta.mejtacore.api.economy.EconomyProvider;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.economy.EconomyService;
import com.mejta.mejtacore.logging.Diagnostics;

/**
 * {@link EconomyService} implementation: checks arguments, hands the work to the registered provider,
 * turns its exceptions into failures and informs listeners.
 */
public final class Economy implements EconomyService {
	private static final Economy INSTANCE = new Economy();
	private static final int DEFAULT_DIGITS = 2;

	private volatile String providerId;
	private volatile EconomyProvider provider;
	private final List<EconomyListener> listeners = new CopyOnWriteArrayList<>();

	private Economy() {
	}

	public static Economy get() {
		return INSTANCE;
	}

	@Override
	public synchronized void registerProvider(String addonId, EconomyProvider provider) {
		Objects.requireNonNull(addonId, "addonId");
		Objects.requireNonNull(provider, "provider");
		if (this.provider != null) {
			throw new IllegalStateException("Addon '" + this.providerId + "' already provides the economy; '" + addonId + "' cannot");
		}
		this.providerId = addonId;
		this.provider = provider;
	}

	/** The provider, if its addon is active. */
	private EconomyProvider active() {
		EconomyProvider p = provider;
		return p != null && MejtaCoreAPI.isAddonActive(providerId) ? p : null;
	}

	@Override
	public boolean isAvailable() {
		return active() != null;
	}

	@Override
	public Optional<String> providerId() {
		return Optional.ofNullable(providerId);
	}

	@Override
	public int fractionDigits() {
		EconomyProvider p = active();
		if (p == null) {
			return DEFAULT_DIGITS;
		}
		int digits = call(p::fractionDigits, DEFAULT_DIGITS);
		return digits >= 0 && digits <= 4 ? digits : DEFAULT_DIGITS;
	}

	@Override
	public String currencyName(long amount) {
		EconomyProvider p = active();
		if (p == null) {
			return "";
		}
		long one = pow10(fractionDigits());
		return amount == one ? call(p::currencySingular, "") : call(p::currencyPlural, "");
	}

	@Override
	public String format(long amount) {
		EconomyProvider p = active();
		String fallback = Amounts.plain(amount, fractionDigits());
		return p == null ? fallback : call(() -> p.format(amount), fallback);
	}

	@Override
	public OptionalLong parse(String text) {
		return Amounts.parse(text, fractionDigits());
	}

	@Override
	public boolean hasAccount(UUID player) {
		EconomyProvider p = active();
		return p != null && call(() -> p.hasAccount(player), false);
	}

	@Override
	public boolean ensureAccount(UUID player, Optional<String> name) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(name, "name");
		EconomyProvider p = active();
		return p != null && call(() -> p.ensureAccount(player, name), false);
	}

	@Override
	public long balance(UUID player) {
		EconomyProvider p = active();
		return p == null ? 0 : call(() -> p.balance(player), 0L);
	}

	@Override
	public boolean has(UUID player, long amount) {
		return amount <= 0 || balance(player) >= amount;
	}

	@Override
	public EconomyResult deposit(UUID player, long amount, String source, String reason) {
		Objects.requireNonNull(player, "player");
		return change(amount, source, reason, p -> p.deposit(player, amount, source, reason),
				r -> fire(player, r.balance() - amount, r.balance(), source, reason), player);
	}

	@Override
	public EconomyResult withdraw(UUID player, long amount, String source, String reason) {
		Objects.requireNonNull(player, "player");
		return change(amount, source, reason, p -> p.withdraw(player, amount, source, reason),
				r -> fire(player, r.balance() + amount, r.balance(), source, reason), player);
	}

	@Override
	public EconomyResult transfer(UUID from, UUID to, long amount, String source, String reason) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if (from.equals(to)) {
			return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, balance(from), "You cannot pay yourself.");
		}
		long[] receiverBefore = new long[1];
		return change(amount, source, reason, p -> {
			receiverBefore[0] = p.balance(to);
			return p.transfer(from, to, amount, source, reason);
		}, r -> {
			fire(from, r.balance() + amount, r.balance(), source, reason);
			fire(to, receiverBefore[0], receiverBefore[0] + amount, source, reason);
		}, from);
	}

	@Override
	public List<BalanceEntry> top(int limit) {
		EconomyProvider p = active();
		if (p == null || limit <= 0) {
			return List.of();
		}
		return call(() -> List.copyOf(p.top(limit)), List.of());
	}

	@Override
	public void addListener(EconomyListener listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	private EconomyResult change(long amount, String source, String reason, Function<EconomyProvider, EconomyResult> change,
			Consumer<EconomyResult> onSuccess, UUID account) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(reason, "reason");
		EconomyProvider p = active();
		if (p == null) {
			return EconomyResult.failure(EconomyResult.Status.NO_PROVIDER, 0, "There is no economy on this server.");
		}
		if (amount <= 0) {
			return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, balance(account), "The amount must be positive.");
		}
		EconomyResult result;
		try {
			result = Objects.requireNonNull(change.apply(p), "provider returned null");
		} catch (RuntimeException e) {
			Diagnostics.error(providerId, "Economy provider failed (" + source + ": " + reason + ")", e);
			return EconomyResult.failure(EconomyResult.Status.FAILED, 0, "The economy failed; nothing was changed.");
		}
		if (result.success()) {
			onSuccess.accept(result);
		}
		return result;
	}

	private void fire(UUID player, long oldBalance, long newBalance, String source, String reason) {
		for (EconomyListener listener : listeners) {
			try {
				listener.onBalanceChange(player, oldBalance, newBalance, source, reason);
			} catch (RuntimeException e) {
				Diagnostics.error("economy", "Economy listener failed", e);
			}
		}
	}

	private <T> T call(Supplier<T> call, T fallback) {
		try {
			T value = call.get();
			return value != null ? value : fallback;
		} catch (RuntimeException e) {
			Diagnostics.error(String.valueOf(providerId), "Economy provider failed", e);
			return fallback;
		}
	}

	private static long pow10(int digits) {
		long v = 1;
		for (int i = 0; i < digits; i++) {
			v *= 10;
		}
		return v;
	}
}
