package com.mejta.mejtamarket;

import java.util.UUID;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/**
 * MejtaMarket's settings, in {@code config/mejta/mejtamarket.json}. Keys are stable. Amounts of money are
 * in whole units of the currency (e.g. {@code 5000} = 5,000 coins); the bot's item prices are in
 * {@code config/mejta/mejtamarket-prices.json}.
 */
public final class MarketSettings {
	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
			.describedAs("The auction house and buy orders are open. Off: nobody can list, buy or fill, but everyone can still collect");

	public static final Setting<Integer> LISTING_MAX = Setting.integer("listing.max_per_player", 10, 1, 200)
			.describedAs("Listings a Member (a player without a rank) may have on sale at once");
	/** @since 1.2.0 */
	public static final Setting<Integer> LISTING_MAX_BRONZE = Setting.integer("listing.max_per_player_bronze", 13, 1, 200)
			.describedAs("Listings a Bronze player may have on sale at once");
	/** @since 1.2.0 */
	public static final Setting<Integer> LISTING_MAX_SILVER = Setting.integer("listing.max_per_player_silver", 16, 1, 200)
			.describedAs("Listings a Silver player may have on sale at once");
	/** @since 1.2.0 */
	public static final Setting<Integer> LISTING_MAX_GOLD = Setting.integer("listing.max_per_player_gold", 20, 1, 200)
			.describedAs("Listings a Gold player may have on sale at once");
	public static final Setting<Integer> LISTING_HOURS = Setting.integer("listing.duration_hours", 48, 1, 720)
			.describedAs("Hours a listing stays on sale; unsold items then go back to the seller's collection box");
	public static final Setting<Integer> MIN_PRICE = Setting.integer("listing.min_price", 1, 0, 1_000_000)
			.describedAs("Lowest price of a listing or of one item in a buy order, in whole units (0 = any price above zero)");
	public static final Setting<Integer> MAX_PRICE = Setting.integer("listing.max_price", 10_000_000, 1, 2_000_000_000)
			.describedAs("Highest price of a listing or a buy order, in whole units");

	public static final Setting<Double> LISTING_FEE = Setting.decimal("fees.listing_percent", 1.0, 0.0, 25.0)
			.describedAs("Percent of the price paid when listing or placing a buy order. Not refunded; the money leaves the economy");
	public static final Setting<Double> SALES_TAX = Setting.decimal("fees.sales_tax_percent", 5.0, 0.0, 50.0)
			.describedAs("Percent of each sale kept back from the seller (or from whoever fills a buy order). The money leaves the economy");

	public static final Setting<Boolean> ORDERS_ENABLED = Setting.bool("orders.enabled", true)
			.describedAs("Players can place buy orders that others fill from their inventory");
	public static final Setting<Integer> ORDERS_MAX = Setting.integer("orders.max_per_player", 5, 1, 100)
			.describedAs("Open buy orders a player may have at once");
	public static final Setting<Integer> ORDERS_HOURS = Setting.integer("orders.duration_hours", 168, 1, 2160)
			.describedAs("Hours a buy order stays open; the unspent money is then refunded");
	public static final Setting<Integer> ORDERS_MAX_AMOUNT = Setting.integer("orders.max_amount", 2304, 1, 100_000)
			.describedAs("Most items one buy order may ask for (2304 = 36 stacks of 64)");

	public static final Setting<Boolean> ANNOUNCE_SALES = Setting.bool("notify.sales", true)
			.describedAs("Tell online sellers when something of theirs sells or a buy order of theirs is filled");
	public static final Setting<Boolean> AUDIT_TRADES = Setting.bool("audit.trades", true)
			.describedAs("Record every sale, fill, cancellation and collection in MejtaCore's audit log (the market's own log always records them)");

	public static final Setting<Boolean> BOT_ENABLED = Setting.bool("bot.enabled", true)
			.describedAs("A market bot buys and sells a little now and then, so the market stays alive with few players online");
	public static final Setting<String> BOT_NAME = Setting.string("bot.name", "Market Trader")
			.validatedBy(s -> !s.isBlank() && s.length() <= 32)
			.describedAs("Name the bot's listings and buy orders are shown with");
	public static final Setting<Integer> BOT_INTERVAL_MIN = Setting.integer("bot.interval_min_minutes", 4, 1, 1440)
			.describedAs("Shortest time between two bot actions, in minutes");
	public static final Setting<Integer> BOT_INTERVAL_MAX = Setting.integer("bot.interval_max_minutes", 12, 1, 1440)
			.describedAs("Longest time between two bot actions, in minutes (a random time in between is picked)");
	public static final Setting<Integer> BOT_FULL_PLAYERS = Setting.integer("bot.full_activity_players", 3, 0, 500)
			.describedAs("Up to this many players online, the bot is fully active");
	public static final Setting<Integer> BOT_MAX_PLAYERS = Setting.integer("bot.max_players", 10, 1, 1000)
			.describedAs("From this many players online the bot stops; in between it acts less and less often");
	public static final Setting<Double> BOT_BUY_FACTOR = Setting.decimal("bot.buy_factor", 0.75, 0.10, 0.95)
			.describedAs("The bot buys at most at this share of an item's reference price");
	public static final Setting<Double> BOT_SELL_FACTOR = Setting.decimal("bot.sell_factor", 1.15, 1.05, 5.0)
			.describedAs("The bot sells at least at this multiple of an item's reference price");
	public static final Setting<Integer> BOT_DAILY_BUDGET = Setting.integer("bot.daily_budget", 5000, 0, 100_000_000)
			.describedAs("Money the bot gets per day to buy with, in whole units. This is the only money it adds to the economy");
	public static final Setting<Integer> BOT_BUDGET_CAP = Setting.integer("bot.budget_cap", 20_000, 0, 1_000_000_000)
			.describedAs("Most money the bot can save up, in whole units");
	public static final Setting<Integer> BOT_DAILY_STOCK = Setting.integer("bot.daily_stock_per_item", 128, 0, 100_000)
			.describedAs("Most items of one kind the bot sells per day");
	public static final Setting<Integer> BOT_MAX_LISTINGS = Setting.integer("bot.max_listings", 12, 0, 200)
			.describedAs("Listings the bot keeps on sale at most");
	public static final Setting<Integer> BOT_MAX_ORDERS = Setting.integer("bot.max_orders", 6, 0, 100)
			.describedAs("Buy orders the bot keeps open at most");

	public static final Setting<Integer> PRICE_HISTORY_DAYS = Setting.integer("prices.history_days", 14, 1, 90)
			.describedAs("Days of player trades used to work out an item's reference price");
	public static final Setting<Integer> PRICE_MIN_SAMPLES = Setting.integer("prices.min_samples", 3, 1, 100)
			.describedAs("Different trades (one per buyer and seller pair and day) needed before trades count; until then the price list is used");
	public static final Setting<Double> PRICE_MAX_DAILY_CHANGE = Setting.decimal("prices.max_daily_change_percent", 10.0, 1.0, 100.0)
			.describedAs("How far an item's reference price may move per day, in percent, so fake trades cannot push it quickly");
	public static final Setting<Double> PRICE_BAND = Setting.decimal("prices.band", 2.0, 1.1, 10.0)
			.describedAs("A reference price stays between the price list's price divided by this and multiplied by it");

	public static final ConfigSpec SPEC = ConfigSpec.builder(1)
			.add(ENABLED, LISTING_MAX, LISTING_MAX_BRONZE, LISTING_MAX_SILVER, LISTING_MAX_GOLD, LISTING_HOURS, MIN_PRICE, MAX_PRICE, LISTING_FEE, SALES_TAX, ORDERS_ENABLED, ORDERS_MAX, ORDERS_HOURS,
					ORDERS_MAX_AMOUNT, ANNOUNCE_SALES, AUDIT_TRADES, BOT_ENABLED, BOT_NAME, BOT_INTERVAL_MIN, BOT_INTERVAL_MAX, BOT_FULL_PLAYERS,
					BOT_MAX_PLAYERS, BOT_BUY_FACTOR, BOT_SELL_FACTOR, BOT_DAILY_BUDGET, BOT_BUDGET_CAP, BOT_DAILY_STOCK, BOT_MAX_LISTINGS,
					BOT_MAX_ORDERS, PRICE_HISTORY_DAYS, PRICE_MIN_SAMPLES, PRICE_MAX_DAILY_CHANGE, PRICE_BAND)
			.build();

	/** Listings the player may have on sale at once, by their MejtaCore rank. */
	public static int listingMax(ConfigHandle config, UUID player) {
		return config.get(MejtaCoreAPI.ranks().byRank(player, LISTING_MAX, LISTING_MAX_BRONZE, LISTING_MAX_SILVER, LISTING_MAX_GOLD));
	}

	private MarketSettings() {
	}
}
