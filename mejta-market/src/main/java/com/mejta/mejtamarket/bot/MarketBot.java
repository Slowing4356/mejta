package com.mejta.mejtamarket.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.random.RandomGenerator;

import com.google.gson.JsonElement;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtamarket.MarketService;
import com.mejta.mejtamarket.MarketSettings;
import com.mejta.mejtamarket.data.BuyOrder;
import com.mejta.mejtamarket.data.Lot;
import com.mejta.mejtamarket.price.PriceBook;
import com.mejta.mejtamarket.util.ItemJson;
import com.mejta.mejtamarket.util.MarketItems;

/**
 * The market bot: now and then, while few players are online, it does one small thing that keeps the
 * market moving. It only trades items on the price list, at prices from {@link BotPolicy} around each
 * item's reference price, within its daily budget and stock (see {@link BotLedger}).
 */
public final class MarketBot {
	private static final int TICKS_PER_MINUTE = 1200;

	private final MarketService market;
	private final RandomGenerator random = RandomGenerator.getDefault();
	private int ticksLeft = -1;

	public MarketBot(MarketService market) {
		this.market = market;
	}

	/** Called every server tick. */
	public void tick(MinecraftServer server) {
		if (ticksLeft < 0) {
			reschedule();
		}
		if (--ticksLeft > 0) {
			return;
		}
		reschedule();
		if (!market.config().get(MarketSettings.BOT_ENABLED) || market.closedReason().isPresent()) {
			return;
		}
		double chance = BotPolicy.activity(activePlayers(server), market.config().get(MarketSettings.BOT_FULL_PLAYERS),
				market.config().get(MarketSettings.BOT_MAX_PLAYERS));
		if (random.nextDouble() < chance) {
			act();
		}
	}

	private void reschedule() {
		ticksLeft = BotPolicy.nextDelayMinutes(random, market.config().get(MarketSettings.BOT_INTERVAL_MIN),
				market.config().get(MarketSettings.BOT_INTERVAL_MAX)) * TICKS_PER_MINUTE;
	}

	/** Online players who are visible and not AFK: the ones who could trade. */
	static int activePlayers(MinecraftServer server) {
		return (int) MejtaCoreAPI.presence().publiclyOnline(server).stream().filter(p -> !MejtaCoreAPI.status().isAfk(p.getUUID())).count();
	}

	/**
	 * Does one thing now, whatever the number of players.
	 *
	 * @return what it did, or empty if there was nothing it may do
	 */
	public Optional<String> act() {
		if (market.closedReason().isPresent()) {
			return Optional.empty();
		}
		long now = System.currentTimeMillis();
		Optional<Lot> cheap = cheapestListing(now);
		Optional<BuyOrder> paying = bestOrder(now);
		List<String> listable = listableItems(now);
		List<String> orderable = orderableItems(now);
		BotPolicy.Action action = BotPolicy.choose(random, cheap.isPresent(), paying.isPresent(), !listable.isEmpty(), !orderable.isEmpty());
		if (action == null) {
			return Optional.empty();
		}
		return switch (action) {
			case BUY_LISTING -> market.botBuy(cheap.get())
					? Optional.of("bought " + cheap.get().count() + " x " + cheap.get().itemId() + " from " + cheap.get().sellerName())
					: Optional.empty();
			case FILL_ORDER -> fill(paying.get(), now);
			case LIST_ITEMS -> list(listable.get(random.nextInt(listable.size())), now);
			case PLACE_ORDER -> order(orderable.get(random.nextInt(orderable.size())), now);
		};
	}

	private long available(long now) {
		return market.ledger().available(now, MarketService.whole(market.config().get(MarketSettings.BOT_DAILY_BUDGET)),
				MarketService.whole(market.config().get(MarketSettings.BOT_BUDGET_CAP)));
	}

	private double buyFactor() {
		return market.config().get(MarketSettings.BOT_BUY_FACTOR);
	}

	private double sellFactor() {
		return market.config().get(MarketSettings.BOT_SELL_FACTOR);
	}

	/** A player listing of a price-list item, plain, affordable and at most the bot's buy price per item: the best deal. */
	private Optional<Lot> cheapestListing(long now) {
		long budget = available(now);
		Lot best = null;
		double bestRatio = Double.MAX_VALUE;
		for (Lot lot : market.store().listed()) {
			if (lot.seller().equals(PriceBook.BOT) || lot.expired(now) || lot.price() > budget || !market.priceList().contains(lot.itemId())
					|| !plain(lot)) {
				continue;
			}
			OptionalLong ref = market.reference(lot.itemId());
			if (ref.isEmpty()) {
				continue;
			}
			long max = BotPolicy.buyPrice(ref.getAsLong(), buyFactor());
			// Compared per item without rounding: a lot of 3 for 10 is 3.33 each.
			if (max <= 0 || lot.price() > max * (long) lot.count()) {
				continue;
			}
			double ratio = (double) lot.price() / lot.count() / ref.getAsLong();
			if (ratio < bestRatio) {
				bestRatio = ratio;
				best = lot;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Whether the lot holds the plain item (no enchantments, names or damage): the only kind the bot buys. */
	private boolean plain(Lot lot) {
		Optional<Item> item = MarketItems.byId(lot.itemId());
		if (item.isEmpty() || market.server() == null) {
			return false;
		}
		try {
			JsonElement plain = ItemJson.encode(market.server().registryAccess(), new ItemStack(item.get()));
			return plain.equals(lot.item());
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** A player buy order for a price-list item that pays at least the bot's sell price, with stock left: the best paying. */
	private Optional<BuyOrder> bestOrder(long now) {
		int dailyStock = market.config().get(MarketSettings.BOT_DAILY_STOCK);
		return market.store().openOrders().stream()
				.filter(o -> !o.owner().equals(PriceBook.BOT) && !o.expired(now) && market.priceList().contains(o.itemId())
						&& market.ledger().stockLeft(o.itemId(), now, dailyStock) > 0)
				.filter(o -> {
					OptionalLong ref = market.reference(o.itemId());
					return ref.isPresent() && o.unitPrice() >= BotPolicy.sellPrice(ref.getAsLong(), buyFactor(), sellFactor());
				})
				.max(Comparator.comparingDouble(o -> (double) o.unitPrice() / market.reference(o.itemId()).orElse(1)));
	}

	private List<String> listableItems(long now) {
		List<String> out = new ArrayList<>();
		long botListings = market.store().listedBy(PriceBook.BOT).size();
		if (botListings >= market.config().get(MarketSettings.BOT_MAX_LISTINGS)) {
			return out;
		}
		int dailyStock = market.config().get(MarketSettings.BOT_DAILY_STOCK);
		List<String> listed = market.store().listedBy(PriceBook.BOT).stream().map(Lot::itemId).toList();
		for (String id : market.priceList().items()) {
			if (!listed.contains(id) && market.ledger().stockLeft(id, now, dailyStock) > 0 && MarketItems.byId(id).isPresent()) {
				out.add(id);
			}
		}
		return out;
	}

	private List<String> orderableItems(long now) {
		List<String> out = new ArrayList<>();
		List<BuyOrder> mine = market.store().ordersOf(PriceBook.BOT);
		if (mine.size() >= market.config().get(MarketSettings.BOT_MAX_ORDERS)) {
			return out;
		}
		long budget = available(now);
		List<String> ordered = mine.stream().map(BuyOrder::itemId).toList();
		for (String id : market.priceList().items()) {
			if (ordered.contains(id) || MarketItems.byId(id).isEmpty()) {
				continue;
			}
			OptionalLong ref = market.reference(id);
			if (ref.isPresent() && BotPolicy.buyPrice(ref.getAsLong(), buyFactor()) > 0
					&& budget >= BotPolicy.buyPrice(ref.getAsLong(), buyFactor())) {
				out.add(id);
			}
		}
		return out;
	}

	private Optional<String> fill(BuyOrder order, long now) {
		int stock = market.ledger().stockLeft(order.itemId(), now, market.config().get(MarketSettings.BOT_DAILY_STOCK));
		int max = Math.min(stock, Math.min(order.remaining(), 64));
		if (max <= 0) {
			return Optional.empty();
		}
		int count = 1 + random.nextInt(max);
		return market.botFill(order, count) ? Optional.of("sold " + count + " x " + order.itemId() + " into " + order.ownerName() + "'s buy order")
				: Optional.empty();
	}

	private Optional<String> list(String itemId, long now) {
		Optional<Item> item = MarketItems.byId(itemId);
		OptionalLong ref = market.reference(itemId);
		if (item.isEmpty() || ref.isEmpty()) {
			return Optional.empty();
		}
		int stack = new ItemStack(item.get()).getMaxStackSize();
		int stock = market.ledger().stockLeft(itemId, now, market.config().get(MarketSettings.BOT_DAILY_STOCK));
		int max = Math.min(stock, stack);
		if (max <= 0) {
			return Optional.empty();
		}
		// A quarter stack to a full stack, so the bot's lots look like a player's.
		int count = Math.min(max, Math.max(1, stack / 4) + random.nextInt(Math.max(1, stack - stack / 4 + 1)));
		long unit = BotPolicy.sellPrice(ref.getAsLong(), buyFactor(), sellFactor());
		long price;
		try {
			price = Math.multiplyExact(unit, (long) count);
		} catch (ArithmeticException e) {
			return Optional.empty();
		}
		return market.botList(item.get(), count, price).map(l -> "listed " + l.count() + " x " + itemId + " for " + MarketService.money(l.price()));
	}

	private Optional<String> order(String itemId, long now) {
		Optional<Item> item = MarketItems.byId(itemId);
		OptionalLong ref = market.reference(itemId);
		if (item.isEmpty() || ref.isEmpty()) {
			return Optional.empty();
		}
		long unit = BotPolicy.buyPrice(ref.getAsLong(), buyFactor());
		int count = BotPolicy.quantity(random, unit, available(now), new ItemStack(item.get()).getMaxStackSize());
		if (count <= 0) {
			return Optional.empty();
		}
		return market.botOrder(item.get(), count, unit)
				.map(o -> "ordered " + o.amount() + " x " + itemId + " at " + MarketService.money(o.unitPrice()) + " each");
	}
}
