package com.mejta.mejtamarket.bot;

import java.util.random.RandomGenerator;

/**
 * The market bot's rules, free of the game so they can be tested.
 *
 * <p><b>No arbitrage.</b> The bot buys at {@code reference * buyFactor} or less (rounded down) and sells at
 * {@code reference * sellFactor} or more (rounded up), with {@code buyFactor < 1 < sellFactor}. Buying from
 * the bot and selling back always loses at least the spread, plus the sales tax on the player's side.
 */
public final class BotPolicy {
	public enum Action {
		/** Buy a player's listing that is cheap enough. */
		BUY_LISTING,
		/** Sell into a player's buy order that pays enough. */
		FILL_ORDER,
		/** Put some of the price list's items on sale. */
		LIST_ITEMS,
		/** Open a buy order that players can sell into. */
		PLACE_ORDER
	}

	private BotPolicy() {
	}

	/**
	 * How likely the bot is to act when its timer fires: {@code 1} with up to {@code fullPlayers} online,
	 * {@code 0} from {@code maxPlayers} on, and falling evenly in between.
	 */
	public static double activity(int online, int fullPlayers, int maxPlayers) {
		if (online <= fullPlayers) {
			return 1.0;
		}
		if (online >= maxPlayers || maxPlayers <= fullPlayers) {
			return 0.0;
		}
		return (double) (maxPlayers - online) / (maxPlayers - fullPlayers);
	}

	/** Highest unit price the bot pays; at least 1 below what it sells for. */
	public static long buyPrice(long reference, double buyFactor) {
		return Math.max(0, (long) Math.floor(reference * Math.min(buyFactor, 0.99)));
	}

	/** Lowest unit price the bot sells for; always above what it pays. */
	public static long sellPrice(long reference, double buyFactor, double sellFactor) {
		long sell = (long) Math.ceil(reference * Math.max(sellFactor, 1.01));
		return Math.max(sell, buyPrice(reference, buyFactor) + 1);
	}

	/** Minutes until the next action: uniformly between the two limits (in either order). */
	public static int nextDelayMinutes(RandomGenerator random, int min, int max) {
		int lo = Math.max(1, Math.min(min, max));
		int hi = Math.max(lo, Math.max(min, max));
		return lo + random.nextInt(hi - lo + 1);
	}

	/**
	 * How many items the bot moves in one action: about a tenth of what it may spend, in whole stacks'
	 * worth of small portions, at least 1 and at most {@code maxCount}.
	 *
	 * @return {@code 0} if not even one item is affordable
	 */
	public static int quantity(RandomGenerator random, long unitPrice, long spendable, int maxCount) {
		if (unitPrice <= 0 || spendable < unitPrice || maxCount <= 0) {
			return 0;
		}
		long affordable = Math.min(maxCount, spendable / unitPrice);
		// Spend up to a tenth of the budget at once, so one action never empties it.
		long portion = Math.max(1, Math.min(affordable, Math.max(1, spendable / 10 / unitPrice)));
		long count = 1 + random.nextLong(portion);
		return (int) Math.min(count, affordable);
	}

	/**
	 * Picks what to do. Actions whose preconditions fail get no weight; buying and selling to players
	 * first (that is what keeps a quiet market moving), then keeping listings and orders stocked.
	 *
	 * @return {@code null} if nothing can be done
	 */
	public static Action choose(RandomGenerator random, boolean cheapListing, boolean payingOrder, boolean mayList, boolean mayOrder) {
		int wBuy = cheapListing ? 4 : 0;
		int wFill = payingOrder ? 4 : 0;
		int wList = mayList ? 2 : 0;
		int wOrder = mayOrder ? 2 : 0;
		int total = wBuy + wFill + wList + wOrder;
		if (total == 0) {
			return null;
		}
		int r = random.nextInt(total);
		if ((r -= wBuy) < 0) {
			return Action.BUY_LISTING;
		}
		if ((r -= wFill) < 0) {
			return Action.FILL_ORDER;
		}
		if ((r -= wList) < 0) {
			return Action.LIST_ITEMS;
		}
		return Action.PLACE_ORDER;
	}
}
