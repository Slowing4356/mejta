package com.mejta.mejtaeconomy.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.economy.BalanceEntry;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.economy.EconomyResult.Status;

/**
 * Every account and its balance, in minor units. Pure logic without game classes, so it can be
 * unit-tested; {@code EcoProvider} saves it after changes. Not thread-safe: used on the server thread.
 *
 * <p>Every change is all or nothing and checked for overflow, and a transfer neither creates nor loses
 * money.
 */
public final class Ledger {
	public static final int FORMAT = 1;
	/** Highest balance an account can ever hold, whatever the settings say (10^15 minor units). */
	public static final long HARD_MAX = 1_000_000_000_000_000L;

	private final int digits;
	private final Map<UUID, Account> accounts = new HashMap<>();
	private long maxBalance = HARD_MAX;
	private long startingBalance;
	private List<Account> ranking;

	/** One player's account. {@code away} is money received while offline, shown on the next join. */
	public static final class Account {
		private final UUID id;
		private String name;
		private long balance;
		private long away;

		Account(UUID id, String name, long balance, long away) {
			this.id = id;
			this.name = name;
			this.balance = balance;
			this.away = away;
		}

		public UUID id() {
			return id;
		}

		public String name() {
			return name;
		}

		public long balance() {
			return balance;
		}

		public long away() {
			return away;
		}
	}

	public Ledger(int digits) {
		if (digits < 0 || digits > 4) {
			throw new IllegalArgumentException("digits must be 0 to 4");
		}
		this.digits = digits;
	}

	public int digits() {
		return digits;
	}

	/** Whole units to minor units, rounded down; e.g. {@code 12.505} with 2 digits is {@code 1250}. */
	public long toMinor(double units) {
		if (!Double.isFinite(units) || units <= 0) {
			return 0;
		}
		BigDecimal minor = BigDecimal.valueOf(units).movePointRight(digits).setScale(0, RoundingMode.DOWN);
		return minor.compareTo(BigDecimal.valueOf(HARD_MAX)) >= 0 ? HARD_MAX : minor.longValue();
	}

	/** The most an account may hold; {@code 0} or more than {@link #HARD_MAX} means {@link #HARD_MAX}. */
	public void setMaxBalance(long max) {
		this.maxBalance = max <= 0 || max > HARD_MAX ? HARD_MAX : max;
	}

	public long maxBalance() {
		return maxBalance;
	}

	/** What a new account starts with. */
	public void setStartingBalance(long amount) {
		this.startingBalance = Math.max(0, Math.min(amount, maxBalance));
	}

	public int size() {
		return accounts.size();
	}

	public Optional<Account> get(UUID player) {
		return Optional.ofNullable(accounts.get(player));
	}

	public boolean has(UUID player) {
		return accounts.containsKey(player);
	}

	public long balance(UUID player) {
		Account a = accounts.get(player);
		return a == null ? 0 : a.balance;
	}

	/** Sum of every balance (money in circulation). */
	public long total() {
		long sum = 0;
		for (Account a : accounts.values()) {
			sum = saturatedAdd(sum, a.balance);
		}
		return sum;
	}

	/**
	 * Creates the account with the starting balance, or updates its name.
	 *
	 * @return whether this created it
	 */
	public boolean ensure(UUID player, Optional<String> name) {
		Account a = accounts.get(player);
		if (a != null) {
			name.filter(n -> !n.isBlank() && !n.equals(a.name)).ifPresent(n -> {
				a.name = n;
				ranking = null;
			});
			return false;
		}
		accounts.put(player, new Account(player, name.filter(n -> !n.isBlank()).orElse(shortId(player)), startingBalance, 0));
		ranking = null;
		return true;
	}

	public EconomyResult deposit(UUID player, long amount) {
		if (amount <= 0) {
			return EconomyResult.failure(Status.INVALID_AMOUNT, balance(player), "The amount must be positive.");
		}
		ensure(player, Optional.empty());
		Account a = accounts.get(player);
		if (amount > maxBalance - a.balance) {
			return EconomyResult.failure(Status.BALANCE_LIMIT, a.balance, a.name + " cannot hold that much money.");
		}
		a.balance += amount;
		ranking = null;
		return EconomyResult.success(a.balance);
	}

	public EconomyResult withdraw(UUID player, long amount) {
		Account a = accounts.get(player);
		if (a == null) {
			return EconomyResult.failure(Status.NO_ACCOUNT, 0, "There is no account for that player.");
		}
		if (amount <= 0) {
			return EconomyResult.failure(Status.INVALID_AMOUNT, a.balance, "The amount must be positive.");
		}
		if (a.balance < amount) {
			return EconomyResult.failure(Status.INSUFFICIENT_FUNDS, a.balance, "Not enough money.");
		}
		a.balance -= amount;
		ranking = null;
		return EconomyResult.success(a.balance);
	}

	/** Moves money; the receiver's account is created if needed. The result carries the payer's balance. */
	public EconomyResult transfer(UUID from, UUID to, long amount) {
		Account payer = accounts.get(from);
		if (payer == null) {
			return EconomyResult.failure(Status.NO_ACCOUNT, 0, "There is no account for the payer.");
		}
		if (amount <= 0 || from.equals(to)) {
			return EconomyResult.failure(Status.INVALID_AMOUNT, payer.balance, from.equals(to) ? "You cannot pay yourself." : "The amount must be positive.");
		}
		if (payer.balance < amount) {
			return EconomyResult.failure(Status.INSUFFICIENT_FUNDS, payer.balance, "Not enough money.");
		}
		Account receiver = accounts.get(to);
		long receiverBalance = receiver == null ? startingBalance : receiver.balance;
		if (amount > maxBalance - receiverBalance) {
			String who = receiver == null ? "The receiver" : receiver.name;
			return EconomyResult.failure(Status.BALANCE_LIMIT, payer.balance, who + " cannot hold that much money.");
		}
		ensure(to, Optional.empty());
		payer.balance -= amount;
		accounts.get(to).balance += amount;
		ranking = null;
		return EconomyResult.success(payer.balance);
	}

	/** Remembers money received while offline. */
	public void addAway(UUID player, long amount) {
		Account a = accounts.get(player);
		if (a != null && amount > 0) {
			a.away = saturatedAdd(a.away, amount);
		}
	}

	/** Returns and clears the money received while offline. */
	public long takeAway(UUID player) {
		Account a = accounts.get(player);
		if (a == null) {
			return 0;
		}
		long away = a.away;
		a.away = 0;
		return away;
	}

	/** Accounts by balance, richest first (ties by name). */
	public List<Account> ranking() {
		if (ranking == null) {
			List<Account> list = new ArrayList<>(accounts.values());
			list.sort(Comparator.comparingLong((Account a) -> a.balance).reversed()
					.thenComparing(a -> a.name, String.CASE_INSENSITIVE_ORDER)
					.thenComparing(a -> a.id));
			ranking = List.copyOf(list);
		}
		return ranking;
	}

	/** 1-based rank of the player, or 0 without an account. */
	public int rank(UUID player) {
		List<Account> list = ranking();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).id.equals(player)) {
				return i + 1;
			}
		}
		return 0;
	}

	public List<BalanceEntry> top(int limit) {
		List<BalanceEntry> out = new ArrayList<>();
		for (Account a : ranking()) {
			if (out.size() >= limit) {
				break;
			}
			out.add(new BalanceEntry(a.id, a.name, a.balance));
		}
		return out;
	}

	/** Finds an account by its last known name, ignoring case. */
	public Optional<Account> byName(String name) {
		for (Account a : accounts.values()) {
			if (a.name.equalsIgnoreCase(name)) {
				return Optional.of(a);
			}
		}
		return Optional.empty();
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("digits", digits);
		JsonArray list = new JsonArray();
		for (Account a : accounts.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", a.id.toString());
			o.addProperty("name", a.name);
			o.addProperty("balance", a.balance);
			if (a.away > 0) {
				o.addProperty("away", a.away);
			}
			list.add(o);
		}
		root.add("accounts", list);
		return root;
	}

	/**
	 * Reads saved accounts into this (empty) ledger. Balances saved with other decimals are converted
	 * (rounded down when there are fewer decimals now). Broken entries are skipped and counted.
	 *
	 * @return the number of skipped entries
	 */
	public int load(JsonObject root) {
		int savedDigits = root.has("digits") ? root.get("digits").getAsInt() : digits;
		int skipped = 0;
		JsonArray list = root.has("accounts") && root.get("accounts").isJsonArray() ? root.getAsJsonArray("accounts") : new JsonArray();
		for (JsonElement e : list) {
			try {
				JsonObject o = e.getAsJsonObject();
				UUID id = UUID.fromString(o.get("id").getAsString());
				String name = o.has("name") ? o.get("name").getAsString() : shortId(id);
				long balance = convert(o.get("balance").getAsLong(), savedDigits);
				long away = o.has("away") ? convert(o.get("away").getAsLong(), savedDigits) : 0;
				if (balance < 0) {
					skipped++;
					continue;
				}
				accounts.put(id, new Account(id, name, Math.min(balance, HARD_MAX), away));
			} catch (RuntimeException ex) {
				skipped++;
			}
		}
		ranking = null;
		return skipped;
	}

	private long convert(long amount, int fromDigits) {
		if (fromDigits == digits) {
			return amount;
		}
		BigDecimal v = BigDecimal.valueOf(amount).movePointRight(digits - fromDigits).setScale(0, RoundingMode.DOWN);
		return v.compareTo(BigDecimal.valueOf(HARD_MAX)) >= 0 ? HARD_MAX : v.longValue();
	}

	private static long saturatedAdd(long a, long b) {
		long r = a + b;
		return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
	}

	private static String shortId(UUID id) {
		return id.toString().substring(0, 8);
	}
}
