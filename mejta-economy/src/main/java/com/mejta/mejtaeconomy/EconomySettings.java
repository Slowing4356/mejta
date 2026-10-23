package com.mejta.mejtaeconomy;

import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** MejtaEconomy's settings, in {@code config/mejta/mejtaeconomy.json}. Keys are stable. */
public final class EconomySettings {
	public enum SymbolPosition {
		/** {@code $12.50} */
		BEFORE,
		/** {@code 12.50$} */
		AFTER,
		/** {@code 12.50} (use {@code currency.show_name} for {@code 12.50 coins}) */
		NONE
	}

	public static final Setting<String> SYMBOL = Setting.string("currency.symbol", "$")
			.validatedBy(s -> s.length() <= 8)
			.describedAs("Currency symbol, at most 8 characters, e.g. $, € or ⛃");
	public static final Setting<SymbolPosition> SYMBOL_POSITION = Setting.enumeration("currency.symbol_position", SymbolPosition.BEFORE)
			.describedAs("Where the symbol goes: before ($12.50), after (12.50$) or nowhere");
	public static final Setting<String> SINGULAR = Setting.string("currency.singular", "coin")
			.validatedBy(s -> !s.isBlank() && s.length() <= 32)
			.describedAs("Name of one unit, e.g. coin");
	public static final Setting<String> PLURAL = Setting.string("currency.plural", "coins")
			.validatedBy(s -> !s.isBlank() && s.length() <= 32)
			.describedAs("Name of several units, e.g. coins");
	public static final Setting<Boolean> SHOW_NAME = Setting.bool("currency.show_name", false)
			.describedAs("Write the currency name after amounts ($12.50 coins); on is best with the symbol set to NONE");
	public static final Setting<Integer> DECIMALS = Setting.integer("currency.decimals", 2, 0, 4)
			.describedAs("Digits after the decimal point (0 for whole coins only). Takes effect on restart; saved balances are converted, rounding down");

	public static final Setting<Double> STARTING_BALANCE = Setting.decimal("accounts.starting_balance", 0, 0, 1_000_000_000_000d)
			.describedAs("Money a player gets on their first join");
	public static final Setting<Double> MAX_BALANCE = Setting.decimal("accounts.max_balance", 0, 0, 1_000_000_000_000d)
			.describedAs("Most money one account may hold. 0 = no limit (beyond 10^15 minor units)");

	public static final Setting<Boolean> PAY_ENABLED = Setting.bool("pay.enabled", true)
			.describedAs("Players can send each other money with /pay");
	public static final Setting<Double> PAY_MIN = Setting.decimal("pay.min_amount", 0.01, 0, 1_000_000_000d)
			.describedAs("Smallest amount /pay accepts");
	public static final Setting<Boolean> PAY_OFFLINE = Setting.bool("pay.offline_players", true)
			.describedAs("Players can pay players who are offline (they must have joined before)");

	public static final Setting<Integer> BALTOP_PAGE = Setting.integer("baltop.page_size", 10, 5, 20)
			.describedAs("Lines per page of /baltop");
	public static final Setting<Boolean> AWAY_SUMMARY = Setting.bool("notify.away_summary", true)
			.describedAs("Tell players on joining how much money they received while offline (sales, payments)");
	public static final Setting<Boolean> LOG_TRANSACTIONS = Setting.bool("log.transactions", true)
			.describedAs("Keep every balance change in mejta-data/mejtaeconomy/transactions.jsonl, for /eco history");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(SYMBOL, SYMBOL_POSITION, SINGULAR, PLURAL, SHOW_NAME, DECIMALS, STARTING_BALANCE, MAX_BALANCE,
					PAY_ENABLED, PAY_MIN, PAY_OFFLINE, BALTOP_PAGE, AWAY_SUMMARY, LOG_TRANSACTIONS)
			.build();

	private EconomySettings() {
	}
}
