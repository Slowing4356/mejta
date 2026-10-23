package com.mejta.mejtamarket.bot;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * The market bot's money and stock limits, and what it did in total.
 *
 * <p><b>Budget.</b> The bot's spending money refills by {@code daily} per day up to {@code cap}. It is the
 * only money the bot ever adds to the economy. Money players pay the bot is not added to the budget: it
 * leaves the economy. {@link #paidOut} and {@link #absorbed} show both sides.
 *
 * <p><b>Stock.</b> The bot creates the items it sells, so it sells at most {@code dailyStock} of each item
 * per (real) day. Items it buys are destroyed.
 *
 * <p>Not thread-safe; used on the server thread.
 */
public final class BotLedger {
	static final long DAY = 86_400_000L;

	private long budget;
	private long budgetMillis = -1;
	private long paidOut;
	private long absorbed;
	private long itemsBought;
	private long itemsSold;
	private long stockDay = -1;
	private final Map<String, Integer> soldToday = new HashMap<>();

	/** Money the bot may spend now (refilled up to the cap for the time since the last call). */
	public long available(long now, long daily, long cap) {
		refill(now, daily, cap);
		return budget;
	}

	private void refill(long now, long daily, long cap) {
		if (budgetMillis < 0) {
			// A new bot starts with one day's money.
			budget = Math.max(0, Math.min(cap, daily));
			budgetMillis = now;
			return;
		}
		if (daily <= 0) {
			budget = Math.max(0, Math.min(cap, budget));
			budgetMillis = now;
			return;
		}
		long elapsed = Math.max(0, now - budgetMillis);
		long added = (long) Math.floor((double) daily * elapsed / DAY);
		if (budget + added >= cap) {
			budget = Math.max(0, cap);
			budgetMillis = now;
		} else if (added > 0) {
			budget += added;
			// Only the time that paid for whole units is used up, so frequent calls lose nothing to rounding.
			budgetMillis = Math.min(now, budgetMillis + (long) Math.ceil((double) added * DAY / daily));
		}
	}

	/** Takes {@code amount} from the budget for a purchase. @return false (and changes nothing) if it is not there */
	public boolean spend(long now, long daily, long cap, long amount) {
		if (amount <= 0 || available(now, daily, cap) < amount) {
			return false;
		}
		budget -= amount;
		paidOut += amount;
		return true;
	}

	/** Gives back money taken with {@link #spend} for a purchase that did not happen. */
	public void refund(long amount) {
		budget += amount;
		paidOut -= amount;
	}

	public void boughtItems(int count) {
		itemsBought += count;
	}

	/** Money a player paid the bot: it leaves the economy. */
	public void received(long amount, int items) {
		absorbed += amount;
		itemsSold += items;
	}

	/** How many more of {@code item} the bot may sell today. */
	public int stockLeft(String item, long now, int dailyStock) {
		rollDay(now);
		return Math.max(0, dailyStock - soldToday.getOrDefault(item, 0));
	}

	/** Counts items the bot put on sale or delivered against today's stock. */
	public void useStock(String item, long now, int count) {
		rollDay(now);
		soldToday.merge(item, count, Integer::sum);
	}

	/** Gives back stock of items that came back unsold. */
	public void returnStock(String item, long now, int count) {
		rollDay(now);
		soldToday.computeIfPresent(item, (k, v) -> v - count > 0 ? v - count : null);
	}

	private void rollDay(long now) {
		long day = Math.floorDiv(now, DAY);
		if (day != stockDay) {
			stockDay = day;
			soldToday.clear();
		}
	}

	public long paidOut() {
		return paidOut;
	}

	public long absorbed() {
		return absorbed;
	}

	public long itemsBought() {
		return itemsBought;
	}

	public long itemsSold() {
		return itemsSold;
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("budget", budget);
		o.addProperty("budget_millis", budgetMillis);
		o.addProperty("paid_out", paidOut);
		o.addProperty("absorbed", absorbed);
		o.addProperty("items_bought", itemsBought);
		o.addProperty("items_sold", itemsSold);
		o.addProperty("stock_day", stockDay);
		JsonObject sold = new JsonObject();
		soldToday.forEach(sold::addProperty);
		o.add("sold_today", sold);
		return o;
	}

	public static BotLedger fromJson(JsonObject o) {
		BotLedger l = new BotLedger();
		try {
			l.budget = o.get("budget").getAsLong();
			l.budgetMillis = o.get("budget_millis").getAsLong();
			l.paidOut = o.get("paid_out").getAsLong();
			l.absorbed = o.get("absorbed").getAsLong();
			l.itemsBought = o.get("items_bought").getAsLong();
			l.itemsSold = o.get("items_sold").getAsLong();
			l.stockDay = o.get("stock_day").getAsLong();
			o.getAsJsonObject("sold_today").entrySet().forEach(e -> l.soldToday.put(e.getKey(), e.getValue().getAsInt()));
		} catch (RuntimeException e) {
			// Damaged: the parts read so far stay; a missing budget clock restarts at one day's money.
		}
		return l;
	}
}
