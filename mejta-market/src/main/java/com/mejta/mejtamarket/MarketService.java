package com.mejta.mejtamarket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.economy.EconomyService;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtamarket.bot.BotLedger;
import com.mejta.mejtamarket.bot.Demand;
import com.mejta.mejtamarket.data.BuyOrder;
import com.mejta.mejtamarket.data.Lot;
import com.mejta.mejtamarket.data.MarketStore;
import com.mejta.mejtamarket.data.Payout;
import com.mejta.mejtamarket.logic.Fees;
import com.mejta.mejtamarket.mixin.PlayerListInvoker;
import com.mejta.mejtamarket.price.PriceBook;
import com.mejta.mejtamarket.price.PriceList;
import com.mejta.mejtamarket.util.ItemJson;
import com.mejta.mejtamarket.util.MarketItems;
import com.mejta.mejtamarket.util.Msg;

/**
 * The market's transactions: listing, buying, cancelling, collecting, and placing and filling buy orders.
 * Everything runs on the server thread, one click at a time, so two players can never buy the same lot.
 *
 * <p><b>Items are never duplicated.</b> The stored lot is the only copy of the items it holds:
 * <ul>
 * <li><b>Listing / filling an order:</b> the lot is written as pending, holding the items; then they leave the
 * inventory and the player is saved; then the lot becomes active. If the server stops in between, the
 * player's next join decides from their inventory which side was saved (as for MejtaCorpse's corpses).</li>
 * <li><b>Collecting:</b> the lot is written without the items (or as gone) before they are handed out.</li>
 * </ul>
 * <b>Money is recorded before it is paid.</b> A buyer pays before the lot is marked sold (and is refunded if
 * that write fails); a seller is paid after it. A crash in between can lose a payment, never make one twice.
 */
public final class MarketService {
	/** Writes a player's save file; throws {@link IllegalStateException} if that is impossible. */
	@FunctionalInterface
	public interface PlayerSaver {
		void save(ServerPlayer player);
	}

	/** The game's own save of one player, as {@code saveAll} does for each. */
	public static final PlayerSaver SAVE_PLAYER = player -> {
		PlayerList list = player.level().getServer().getPlayerList();
		if (list.getPlayer(player.getUUID()) != player) {
			throw new IllegalStateException(player.getGameProfile().name() + " is not in the player list");
		}
		((PlayerListInvoker) list).mejtamarket$save(player);
	};

	static final long HOUR = 3_600_000L;
	private static final String PRICES_KEY = "prices";
	private static final String BOT_KEY = "bot";
	private static final String DEMAND_KEY = "demand";

	private final ConfigHandle config;
	private final MarketStore store;
	private final RecordLog tradeLog;
	private PriceBook prices = new PriceBook();
	private PriceList priceList = PriceList.withDefaults();
	private BotLedger ledger = new BotLedger();
	private Demand demand = new Demand();
	private PlayerSaver saver = SAVE_PLAYER;
	private volatile MinecraftServer server;

	public MarketService(ConfigHandle config, MarketStore store, RecordLog tradeLog) {
		this.config = config;
		this.store = store;
		this.tradeLog = tradeLog;
	}

	// ---------------------------------------------------------------- lifecycle

	public void serverStarted(MinecraftServer srv, PriceList list) {
		server = srv;
		priceList = list;
		List<String> damaged = store.load();
		for (String key : damaged) {
			MejtaMarket.log().warn("Skipped damaged market record {}; it is left on disk for staff to check", key);
		}
		prices = store.read(PRICES_KEY).map(PriceBook::fromJson).orElseGet(PriceBook::new);
		ledger = store.read(BOT_KEY).map(BotLedger::fromJson).orElseGet(BotLedger::new);
		demand = store.read(DEMAND_KEY).map(Demand::fromJson).orElseGet(Demand::new);
	}

	public void serverStopped() {
		saveState();
		server = null;
	}

	/** Queues the price history and the bot's ledger for writing (they move no items). */
	public void saveState() {
		prices.prune(System.currentTimeMillis(), config.get(MarketSettings.PRICE_HISTORY_DAYS));
		store.writeLater(PRICES_KEY, prices.toJson());
		store.writeLater(BOT_KEY, ledger.toJson());
		demand.prune(System.currentTimeMillis());
		store.writeLater(DEMAND_KEY, demand.toJson());
	}

	/** For game tests: fake players cannot be saved by the game. */
	public void setSaver(PlayerSaver saver) {
		this.saver = saver;
	}

	public MarketStore store() {
		return store;
	}

	public PriceBook prices() {
		return prices;
	}

	public PriceList priceList() {
		return priceList;
	}

	public void setPriceList(PriceList list) {
		this.priceList = list;
	}

	public BotLedger ledger() {
		return ledger;
	}

	/** What players searched for recently (the bot lists those items more often). */
	public Demand demand() {
		return demand;
	}

	public ConfigHandle config() {
		return config;
	}

	public MinecraftServer server() {
		return server;
	}

	static EconomyService eco() {
		return MejtaCoreAPI.economy();
	}

	/** Whole units of the currency (from a setting) in minor units. */
	public static long whole(long units) {
		long factor = 1;
		for (int i = 0; i < eco().fractionDigits(); i++) {
			factor *= 10;
		}
		return units * factor;
	}

	public static String money(long amount) {
		return eco().format(amount);
	}

	public String botName() {
		return config.get(MarketSettings.BOT_NAME);
	}

	public PriceBook.Params priceParams() {
		return new PriceBook.Params(config.get(MarketSettings.PRICE_HISTORY_DAYS), config.get(MarketSettings.PRICE_MIN_SAMPLES),
				config.get(MarketSettings.PRICE_MAX_DAILY_CHANGE) / 100.0, config.get(MarketSettings.PRICE_BAND));
	}

	/** The item's reference price per item now, if known (see {@link PriceBook}). */
	public OptionalLong reference(String itemId) {
		return prices.reference(itemId, System.currentTimeMillis(), priceList.price(itemId, eco().fractionDigits()), priceParams());
	}

	/** Why the market cannot be used right now, or empty if it can. */
	public Optional<String> closedReason() {
		if (!eco().isAvailable()) {
			return Optional.of("The market needs an economy mod (such as MejtaEconomy), and none is running.");
		}
		if (!config.get(MarketSettings.ENABLED)) {
			return Optional.of("The market is closed right now. You can still collect your items with /ah collect.");
		}
		return Optional.empty();
	}

	private boolean refuseIfClosed(ServerPlayer player) {
		Optional<String> why = closedReason();
		why.ifPresent(w -> player.sendSystemMessage(Msg.error(w)));
		return why.isPresent();
	}

	private Optional<String> checkPrice(long price) {
		long min = whole(config.get(MarketSettings.MIN_PRICE));
		long max = whole(config.get(MarketSettings.MAX_PRICE));
		if (price <= 0 || price < min) {
			return Optional.of("The lowest price is " + money(Math.max(1, min)) + ".");
		}
		if (price > max) {
			return Optional.of("The highest price is " + money(max) + ".");
		}
		return Optional.empty();
	}

	private static String name(ServerPlayer player) {
		return player.getGameProfile().name();
	}

	private HolderLookup.Provider registries() {
		return server.registryAccess();
	}

	// ---------------------------------------------------------------- listing

	/**
	 * Puts {@code count} of the item in the player's main hand on sale for {@code price} in all.
	 *
	 * @return the new lot, or empty (the player was told why)
	 */
	public Optional<Lot> list(ServerPlayer player, long price, int count) {
		if (refuseIfClosed(player)) {
			return Optional.empty();
		}
		int slot = MarketItems.mainHandSlot(player);
		Inventory inventory = player.getInventory();
		ItemStack held = slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot);
		if (held.isEmpty()) {
			player.sendSystemMessage(Msg.error("Hold the items you want to sell in your main hand."));
			return Optional.empty();
		}
		int n = count <= 0 ? held.getCount() : count;
		if (n > held.getCount()) {
			player.sendSystemMessage(Msg.error("You only hold " + held.getCount() + "."));
			return Optional.empty();
		}
		Optional<String> bad = checkPrice(price);
		if (bad.isPresent()) {
			player.sendSystemMessage(Msg.error(bad.get()));
			return Optional.empty();
		}
		int max = MarketSettings.listingMax(config, player.getUUID());
		if (store.listedBy(player.getUUID()).size() >= max) {
			player.sendSystemMessage(Msg.error("You already have " + max + " listings on sale. Cancel one or wait until one sells."));
			return Optional.empty();
		}
		long fee = Fees.percentOf(price, config.get(MarketSettings.LISTING_FEE));
		JsonElement item;
		JsonElement before;
		try {
			item = ItemJson.encode(registries(), held.copyWithCount(1));
			before = ItemJson.encode(registries(), held);
		} catch (RuntimeException e) {
			player.sendSystemMessage(Msg.error("This item cannot be sold on the market."));
			return Optional.empty();
		}
		if (fee > 0) {
			EconomyResult paid = eco().withdraw(player.getUUID(), fee, MejtaMarket.ID, "market listing fee");
			if (!paid.success()) {
				player.sendSystemMessage(Msg.error("You need " + money(fee) + " for the listing fee. " + paid.message()));
				return Optional.empty();
			}
		}
		long now = System.currentTimeMillis();
		Lot pending = Lot.pendingListing(store.newId(), item, MarketItems.id(held), held.getHoverName().getString(),
				MarketItems.category(held).name(), n, player.getUUID(), name(player), price, now,
				now + config.get(MarketSettings.LISTING_HOURS) * HOUR, Map.of(slot, before));
		// 1. The lot with the items, on disk.
		try {
			store.saveNow(pending);
		} catch (IOException e) {
			refund(player.getUUID(), fee, "market listing fee refund");
			MejtaMarket.log().error("Could not save a listing of {}", name(player), e);
			player.sendSystemMessage(Msg.error("The market could not save your listing. Nothing was taken."));
			return Optional.empty();
		}
		// 2. The items out of the inventory, and the player's save without them.
		ItemStack old = held.copy();
		held.shrink(n);
		inventory.setItem(slot, held.isEmpty() ? ItemStack.EMPTY : held);
		try {
			saver.save(player);
		} catch (RuntimeException e) {
			inventory.setItem(slot, old);
			store.discard(pending);
			refund(player.getUUID(), fee, "market listing fee refund");
			MejtaMarket.log().error("Could not save {} after taking the items of a listing; nothing was listed", name(player), e);
			player.sendSystemMessage(Msg.error("The market could not save your listing. Nothing was taken."));
			return Optional.empty();
		}
		// 3. On sale.
		Lot listed = activateListing(pending);
		trade("list", player.getUUID(), name(player), listed, price, Map.of("fee", fee));
		player.sendSystemMessage(Msg.success("Listed " + n + " x " + listed.itemName() + " for " + money(price)
				+ (fee > 0 ? " (fee " + money(fee) + ")" : "") + ". It stays on sale for " + config.get(MarketSettings.LISTING_HOURS) + " hours."));
		return Optional.of(listed);
	}

	private Lot activateListing(Lot pending) {
		Lot listed = pending.listed();
		try {
			store.saveNow(listed);
		} catch (IOException e) {
			MejtaMarket.log().error("Could not activate listing {}; it is completed when {} next joins", pending.id(), pending.sellerName(), e);
			return pending;
		}
		return listed;
	}

	// ---------------------------------------------------------------- buying

	public enum BuyResult {
		BOUGHT,
		GONE,
		OWN,
		NO_MONEY,
		FAILED,
		CLOSED
	}

	/** Buys a whole listing. The items go to the buyer's inventory, or their collection box if it is full. */
	public BuyResult buy(ServerPlayer player, String lotId) {
		if (refuseIfClosed(player)) {
			return BuyResult.CLOSED;
		}
		Optional<Lot> found = store.lot(lotId).filter(l -> l.state() == Lot.State.LISTED);
		long now = System.currentTimeMillis();
		if (found.isEmpty() || found.get().expired(now)) {
			player.sendSystemMessage(Msg.error("That listing is no longer on sale."));
			return BuyResult.GONE;
		}
		Lot lot = found.get();
		if (lot.seller().equals(player.getUUID())) {
			player.sendSystemMessage(Msg.error("That is your own listing. Cancel it from \"Your listings\" instead."));
			return BuyResult.OWN;
		}
		boolean fromBot = lot.seller().equals(PriceBook.BOT);
		String reason = "market purchase #" + lot.id();
		EconomyResult paid = eco().withdraw(player.getUUID(), lot.price(), MejtaMarket.ID, reason);
		if (!paid.success()) {
			player.sendSystemMessage(Msg.error(paid.status() == EconomyResult.Status.INSUFFICIENT_FUNDS
					? "You need " + money(lot.price()) + " to buy this." : "Payment failed: " + paid.message()));
			return BuyResult.NO_MONEY;
		}
		Lot sold = lot.sold(player.getUUID(), name(player), now);
		try {
			store.saveNow(sold);
		} catch (IOException e) {
			refund(player.getUUID(), lot.price(), "market purchase refund #" + lot.id());
			MejtaMarket.log().error("Could not save the sale of {}; the buyer was refunded", lot.id(), e);
			player.sendSystemMessage(Msg.error("The market could not save the purchase. Your money was refunded."));
			return BuyResult.FAILED;
		}
		long tax = 0;
		if (fromBot) {
			ledger.received(lot.price(), lot.count());
		} else {
			long proceeds = Fees.proceeds(lot.price(), config.get(MarketSettings.SALES_TAX));
			tax = lot.price() - proceeds;
			pay(lot.seller(), lot.sellerName(), proceeds, "market sale #" + lot.id());
			prices.record(lot.itemId(), now, lot.price(), lot.count(), player.getUUID(), lot.seller());
			tell(lot.seller(), Msg.success(name(player) + " bought your " + lot.count() + " x " + lot.itemName() + " for " + money(lot.price())
					+ (tax > 0 ? " (you get " + money(proceeds) + " after tax)" : "") + "."));
		}
		trade("buy", player.getUUID(), name(player), sold, lot.price(), Map.of("seller", lot.sellerName(), "tax", tax));
		player.sendSystemMessage(Msg.success("Bought " + lot.count() + " x " + lot.itemName() + " for " + money(lot.price()) + "."));
		collect(player, sold.id(), true);
		saveState();
		return BuyResult.BOUGHT;
	}

	/** The market bot buys a listing: the seller is paid from the bot's budget and the items are destroyed. */
	public boolean botBuy(Lot lot) {
		long now = System.currentTimeMillis();
		if (lot.state() != Lot.State.LISTED || lot.expired(now) || lot.seller().equals(PriceBook.BOT)) {
			return false;
		}
		long daily = whole(config.get(MarketSettings.BOT_DAILY_BUDGET));
		long cap = whole(config.get(MarketSettings.BOT_BUDGET_CAP));
		if (!ledger.spend(now, daily, cap, lot.price())) {
			return false;
		}
		try {
			store.finish(lot);
		} catch (IOException e) {
			ledger.refund(lot.price());
			MejtaMarket.log().error("The market bot could not record buying {}", lot.id(), e);
			return false;
		}
		ledger.boughtItems(lot.count());
		long proceeds = Fees.proceeds(lot.price(), config.get(MarketSettings.SALES_TAX));
		pay(lot.seller(), lot.sellerName(), proceeds, "market sale #" + lot.id());
		tell(lot.seller(), Msg.success(botName() + " bought your " + lot.count() + " x " + lot.itemName() + " for " + money(lot.price())
				+ (proceeds < lot.price() ? " (you get " + money(proceeds) + " after tax)" : "") + "."));
		trade("bot_buy", PriceBook.BOT, botName(), lot.sold(PriceBook.BOT, botName(), now), lot.price(), Map.of("seller", lot.sellerName()));
		saveState();
		return true;
	}

	/** The market bot puts items from the price list on sale (they are created; counted against its daily stock). */
	public Optional<Lot> botList(Item item, int count, long price) {
		long now = System.currentTimeMillis();
		ItemStack one = new ItemStack(item);
		String itemId = MarketItems.id(item);
		JsonElement json;
		try {
			json = ItemJson.encode(registries(), one);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
		Lot lot = Lot.botListing(store.newId(), json, itemId, one.getHoverName().getString(), MarketItems.category(one).name(), count,
				PriceBook.BOT, botName(), price, now, now + config.get(MarketSettings.LISTING_HOURS) * HOUR);
		try {
			store.saveNow(lot);
		} catch (IOException e) {
			MejtaMarket.log().error("The market bot could not save a listing", e);
			return Optional.empty();
		}
		ledger.useStock(itemId, now, count);
		trade("bot_list", PriceBook.BOT, botName(), lot, price, Map.of());
		saveState();
		return Optional.of(lot);
	}

	// ---------------------------------------------------------------- cancelling and expiry

	/** The seller (or staff) takes a listing off sale; the items go to the seller's collection box. */
	public boolean cancel(ServerPlayer player, String lotId, boolean staff) {
		Optional<Lot> found = store.lot(lotId).filter(l -> l.state() == Lot.State.LISTED);
		if (found.isEmpty()) {
			player.sendSystemMessage(Msg.error("That listing is no longer on sale."));
			return false;
		}
		Lot lot = found.get();
		if (!staff && !lot.seller().equals(player.getUUID())) {
			player.sendSystemMessage(Msg.error("That is not your listing."));
			return false;
		}
		if (!returnLot(lot, staff && !lot.seller().equals(player.getUUID()) ? "Removed by staff" : "Cancelled")) {
			player.sendSystemMessage(Msg.error("The market could not save that. Try again."));
			return false;
		}
		if (staff && !lot.seller().equals(player.getUUID())) {
			MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaMarket.ID, "market.remove")
					.actor(MejtaCoreAPI.actor(player)).target(lot.seller(), lot.sellerName()).outcome(AuditOutcome.SUCCESS)
					.detail("lot", lot.id()).detail("item", lot.itemId() + " x" + lot.count()).build(), MarketPermissions.ADMIN);
			player.sendSystemMessage(Msg.success("Removed listing " + lot.id() + "; the items went back to " + lot.sellerName() + "."));
		} else {
			player.sendSystemMessage(Msg.success("Cancelled. The items are in your collection box."));
			collect(player, lot.id(), true);
		}
		return true;
	}

	/** The bot takes one of its own listings off sale (to make room for something players look for). */
	public boolean botWithdraw(Lot lot) {
		return lot.seller().equals(PriceBook.BOT) && lot.state() == Lot.State.LISTED && returnLot(lot, "Withdrawn");
	}

	/** Takes a listing off sale; a player's items go to their collection box, the bot's are destroyed. */
	private boolean returnLot(Lot lot, String why) {
		try {
			if (lot.seller().equals(PriceBook.BOT)) {
				store.finish(lot);
				ledger.returnStock(lot.itemId(), System.currentTimeMillis(), lot.count());
			} else {
				store.saveNow(lot.returned(why));
			}
		} catch (IOException e) {
			MejtaMarket.log().error("Could not take listing {} off sale", lot.id(), e);
			return false;
		}
		trade("return", lot.seller(), lot.sellerName(), lot, lot.price(), Map.of("why", why));
		return true;
	}

	/** Ends expired listings and buy orders (run every few seconds). */
	public void expire() {
		long now = System.currentTimeMillis();
		for (Lot lot : store.listed()) {
			if (lot.expired(now) && returnLot(lot, "Expired") && !lot.seller().equals(PriceBook.BOT)) {
				tell(lot.seller(), Msg.info("Your listing of " + lot.count() + " x " + lot.itemName() + " expired. Get it back with /ah collect."));
			}
		}
		for (BuyOrder order : store.openOrders()) {
			if (order.expired(now)) {
				closeOrder(order, "Expired");
			}
		}
	}

	// ---------------------------------------------------------------- collection box

	/**
	 * Hands out what fits of one lot in the player's collection box.
	 *
	 * @param quiet no message if it was all handed out (after a purchase, which has its own message)
	 * @return how many items were handed out
	 */
	public int collect(ServerPlayer player, String lotId, boolean quiet) {
		Optional<Lot> found = store.lot(lotId).filter(l -> l.state().collectable() && player.getUUID().equals(l.holder()));
		if (found.isEmpty()) {
			return 0;
		}
		Lot lot = found.get();
		Optional<ItemStack> template = ItemJson.decode(registries(), lot.item()).filter(s -> !s.isEmpty());
		if (template.isEmpty()) {
			player.sendSystemMessage(Msg.error("The items of " + lot.id() + " no longer exist in this game (a removed mod?). Staff can check it."));
			return 0;
		}
		int fit = Math.min(lot.count(), MarketItems.space(player.getInventory(), template.get()));
		if (fit <= 0) {
			player.sendSystemMessage(Msg.error("Your inventory is full. " + lot.count() + " x " + lot.itemName()
					+ " wait in your collection box (/ah collect)."));
			return 0;
		}
		// Written without the items first; only then are they handed out.
		try {
			if (fit == lot.count()) {
				store.finish(lot);
			} else {
				store.saveNow(lot.withCount(lot.count() - fit));
			}
		} catch (IOException e) {
			MejtaMarket.log().error("Could not save collecting {} by {}", lot.id(), name(player), e);
			player.sendSystemMessage(Msg.error("The market could not save that. Nothing was handed out; try again."));
			return 0;
		}
		MarketItems.give(player, template.get(), fit);
		if (config.get(MarketSettings.AUDIT_TRADES)) {
			tradeLog.append(record("collect", player.getUUID(), name(player), lot, fit));
		}
		if (fit < lot.count()) {
			player.sendSystemMessage(Msg.info("Took " + fit + " x " + lot.itemName() + "; " + (lot.count() - fit)
					+ " more wait in your collection box (your inventory is full)."));
		} else if (!quiet) {
			player.sendSystemMessage(Msg.success("Took " + fit + " x " + lot.itemName() + "."));
		}
		return fit;
	}

	/** Collects money owed to the player. */
	public boolean collectPayout(ServerPlayer player, String payoutId) {
		Optional<Payout> found = store.payout(payoutId).filter(p -> p.holder().equals(player.getUUID()));
		if (found.isEmpty()) {
			return false;
		}
		Payout p = found.get();
		try {
			store.finish(p);
		} catch (IOException e) {
			player.sendSystemMessage(Msg.error("The market could not save that. Try again."));
			return false;
		}
		EconomyResult r = eco().deposit(p.holder(), p.amount(), MejtaMarket.ID, p.reason());
		if (!r.success()) {
			// Put back what could not be paid.
			owe(p.holder(), p.amount(), p.reason());
			player.sendSystemMessage(Msg.error("Could not pay you " + money(p.amount()) + ": " + r.message()));
			return false;
		}
		player.sendSystemMessage(Msg.success("Collected " + money(p.amount()) + " (" + p.reason() + ")."));
		return true;
	}

	/** Collects everything that fits. @return how many items and payments were handed out */
	public int collectAll(ServerPlayer player) {
		int done = 0;
		for (Payout p : store.payoutsOf(player.getUUID())) {
			done += collectPayout(player, p.id()) ? 1 : 0;
		}
		for (Lot lot : store.collectable(player.getUUID())) {
			int got = collect(player, lot.id(), false);
			done += got;
			if (got < lot.count()) {
				break;
			}
		}
		if (done == 0 && store.collectable(player.getUUID()).isEmpty() && store.payoutsOf(player.getUUID()).isEmpty()) {
			player.sendSystemMessage(Msg.info("Your collection box is empty."));
		}
		return done;
	}

	public int waiting(UUID player) {
		return store.collectable(player).size() + store.payoutsOf(player).size();
	}

	// ---------------------------------------------------------------- buy orders

	/** Places a buy order for {@code amount} of {@code item} at {@code unitPrice} each; the money is taken now. */
	public Optional<BuyOrder> placeOrder(ServerPlayer player, Item item, int amount, long unitPrice) {
		if (refuseIfClosed(player)) {
			return Optional.empty();
		}
		if (!config.get(MarketSettings.ORDERS_ENABLED)) {
			player.sendSystemMessage(Msg.error("Buy orders are switched off on this server."));
			return Optional.empty();
		}
		int maxAmount = config.get(MarketSettings.ORDERS_MAX_AMOUNT);
		if (amount <= 0 || amount > maxAmount) {
			player.sendSystemMessage(Msg.error("A buy order can ask for 1 to " + maxAmount + " items."));
			return Optional.empty();
		}
		Optional<String> bad = checkPrice(unitPrice);
		long total = Fees.total(unitPrice, amount);
		if (bad.isEmpty() && (total < 0 || total > whole(config.get(MarketSettings.MAX_PRICE)))) {
			bad = Optional.of("That order is too large: at most " + money(whole(config.get(MarketSettings.MAX_PRICE))) + " in all.");
		}
		if (bad.isPresent()) {
			player.sendSystemMessage(Msg.error(bad.get()));
			return Optional.empty();
		}
		int max = config.get(MarketSettings.ORDERS_MAX);
		if (store.ordersOf(player.getUUID()).size() >= max) {
			player.sendSystemMessage(Msg.error("You already have " + max + " open buy orders."));
			return Optional.empty();
		}
		long fee = Fees.percentOf(total, config.get(MarketSettings.LISTING_FEE));
		ItemStack one = new ItemStack(item);
		long now = System.currentTimeMillis();
		BuyOrder order = BuyOrder.open(store.newId(), player.getUUID(), name(player), MarketItems.id(item), one.getHoverName().getString(),
				MarketItems.category(one).name(), amount, unitPrice, now, now + config.get(MarketSettings.ORDERS_HOURS) * HOUR);
		EconomyResult paid = eco().withdraw(player.getUUID(), total + fee, MejtaMarket.ID, "market buy order #" + order.id());
		if (!paid.success()) {
			player.sendSystemMessage(Msg.error("You need " + money(total + fee) + " for this order" + (fee > 0 ? " (including the fee)" : "")
					+ ". " + paid.message()));
			return Optional.empty();
		}
		try {
			store.saveNow(order);
		} catch (IOException e) {
			refund(player.getUUID(), total + fee, "market buy order refund #" + order.id());
			MejtaMarket.log().error("Could not save a buy order of {}", name(player), e);
			player.sendSystemMessage(Msg.error("The market could not save your order. Your money was refunded."));
			return Optional.empty();
		}
		orderLog("order", player.getUUID(), name(player), order, total, Map.of("fee", fee));
		player.sendSystemMessage(Msg.success("Buy order placed: " + amount + " x " + order.itemName() + " at " + money(unitPrice) + " each ("
				+ money(total) + " set aside" + (fee > 0 ? ", fee " + money(fee) : "") + ")."));
		return Optional.of(order);
	}

	/** The market bot opens a buy order, paid from its budget. */
	public Optional<BuyOrder> botOrder(Item item, int amount, long unitPrice) {
		long now = System.currentTimeMillis();
		long total = Fees.total(unitPrice, amount);
		long daily = whole(config.get(MarketSettings.BOT_DAILY_BUDGET));
		long cap = whole(config.get(MarketSettings.BOT_BUDGET_CAP));
		if (total <= 0 || !ledger.spend(now, daily, cap, total)) {
			return Optional.empty();
		}
		ItemStack one = new ItemStack(item);
		BuyOrder order = BuyOrder.open(store.newId(), PriceBook.BOT, botName(), MarketItems.id(item), one.getHoverName().getString(),
				MarketItems.category(one).name(), amount, unitPrice, now, now + config.get(MarketSettings.LISTING_HOURS) * HOUR);
		try {
			store.saveNow(order);
		} catch (IOException e) {
			ledger.refund(total);
			return Optional.empty();
		}
		orderLog("bot_order", PriceBook.BOT, botName(), order, total, Map.of());
		saveState();
		return Optional.of(order);
	}

	/** The owner (or staff) cancels an order; the unspent money goes back. */
	public boolean cancelOrder(ServerPlayer player, String orderId, boolean staff) {
		Optional<BuyOrder> found = store.order(orderId).filter(o -> o.state() == BuyOrder.State.OPEN);
		if (found.isEmpty()) {
			player.sendSystemMessage(Msg.error("That buy order is no longer open."));
			return false;
		}
		BuyOrder order = found.get();
		boolean own = order.owner().equals(player.getUUID());
		if (!own && !staff) {
			player.sendSystemMessage(Msg.error("That is not your buy order."));
			return false;
		}
		if (!closeOrder(order, own ? "Cancelled" : "Removed by staff")) {
			player.sendSystemMessage(Msg.error("The market could not save that. Try again."));
			return false;
		}
		if (!own) {
			MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaMarket.ID, "market.remove_order")
					.actor(MejtaCoreAPI.actor(player)).target(order.owner(), order.ownerName()).outcome(AuditOutcome.SUCCESS)
					.detail("order", order.id()).detail("item", order.itemId()).build(), MarketPermissions.ADMIN);
		}
		player.sendSystemMessage(Msg.success("Buy order " + (own ? "cancelled" : "removed") + "; " + money(order.escrow()) + " went back"
				+ (own ? " to you." : " to " + order.ownerName() + ".")));
		return true;
	}

	/** Closes an order (written first), then refunds what was not spent. */
	private boolean closeOrder(BuyOrder order, String why) {
		long refund = order.escrow();
		try {
			store.saveNow(order.closed(why));
		} catch (IOException e) {
			MejtaMarket.log().error("Could not close buy order {}", order.id(), e);
			return false;
		}
		if (order.owner().equals(PriceBook.BOT)) {
			ledger.refund(refund);
		} else if (refund > 0) {
			pay(order.owner(), order.ownerName(), refund, "market buy order refund #" + order.id());
			if (!"Cancelled".equals(why)) {
				tell(order.owner(), Msg.info("Your buy order for " + order.itemName() + " ended (" + why.toLowerCase(java.util.Locale.ROOT)
						+ "); " + money(refund) + " went back to you."));
			}
		}
		orderLog("close", order.owner(), order.ownerName(), order, refund, Map.of("why", why));
		saveState();
		return true;
	}

	public enum FillResult {
		FILLED,
		GONE,
		OWN,
		NO_ITEMS,
		FAILED,
		CLOSED
	}

	/**
	 * The player sells up to {@code max} matching items from their inventory into an order. They are paid
	 * the order's price minus the sales tax; the items go to the owner's collection box.
	 */
	public FillResult fill(ServerPlayer player, String orderId, int max) {
		if (refuseIfClosed(player)) {
			return FillResult.CLOSED;
		}
		long now = System.currentTimeMillis();
		Optional<BuyOrder> found = store.order(orderId).filter(o -> o.state() == BuyOrder.State.OPEN && !o.expired(now));
		if (found.isEmpty()) {
			player.sendSystemMessage(Msg.error("That buy order is no longer open."));
			return FillResult.GONE;
		}
		BuyOrder order = found.get();
		if (order.owner().equals(player.getUUID())) {
			player.sendSystemMessage(Msg.error("You cannot fill your own buy order."));
			return FillResult.OWN;
		}
		Inventory inventory = player.getInventory();
		int n = Math.min(Math.min(max, order.remaining()), MarketItems.countPlain(inventory, order.itemId()));
		if (n <= 0) {
			player.sendSystemMessage(Msg.error("You have no plain " + order.itemName() + " (without enchantments, names or damage) to sell."));
			return FillResult.NO_ITEMS;
		}
		Optional<Map<Integer, ItemStack>> plan = MarketItems.planTake(inventory, order.itemId(), n);
		if (plan.isEmpty()) {
			return FillResult.NO_ITEMS;
		}
		Map<Integer, JsonElement> before = new HashMap<>();
		JsonElement item;
		try {
			for (Map.Entry<Integer, ItemStack> e : plan.get().entrySet()) {
				before.put(e.getKey(), ItemJson.encode(registries(), e.getValue()));
			}
			item = ItemJson.encode(registries(), plan.get().values().iterator().next().copyWithCount(1));
		} catch (RuntimeException e) {
			return FillResult.FAILED;
		}
		long pay = order.unitPrice() * n;
		Lot pending = Lot.pendingFill(store.newId(), item, order.itemId(), order.itemName(), order.category(), n, order.owner(),
				order.ownerName(), player.getUUID(), name(player), now, order.id(), pay, before);
		// 1. The fill with the items, on disk.
		try {
			store.saveNow(pending);
		} catch (IOException e) {
			player.sendSystemMessage(Msg.error("The market could not save the sale. Nothing was taken."));
			return FillResult.FAILED;
		}
		// 2. The items out of the inventory, and the player's save without them.
		Map<Integer, ItemStack> old = MarketItems.take(inventory, plan.get(), n);
		try {
			saver.save(player);
		} catch (RuntimeException e) {
			old.forEach(inventory::setItem);
			store.discard(pending);
			MejtaMarket.log().error("Could not save {} after taking the items of a fill; nothing was sold", name(player), e);
			player.sendSystemMessage(Msg.error("The market could not save the sale. Nothing was taken."));
			return FillResult.FAILED;
		}
		// 3. Counted in the order, delivered and paid.
		completeFill(pending);
		return FillResult.FILLED;
	}

	/**
	 * Finishes a fill whose items have left the filler's (saved) inventory: counts it in the order once,
	 * delivers the items and pays the filler. Also runs at the filler's join after an interrupted fill.
	 */
	private void completeFill(Lot pending) {
		Optional<BuyOrder> found = store.order(pending.orderId());
		long now = System.currentTimeMillis();
		BuyOrder order = found.orElse(null);
		if (order != null && !order.fills().contains(pending.id())) {
			if (order.state() != BuyOrder.State.OPEN || order.remaining() < pending.count()) {
				order = null;
			} else {
				try {
					store.saveNow(order.withFill(pending.id(), pending.count()));
					order = store.order(order.id()).orElse(order);
				} catch (IOException e) {
					MejtaMarket.log().error("Could not count fill {} in order {}; it is finished at {}'s next join", pending.id(),
							pending.orderId(), pending.sellerName(), e);
					return;
				}
			}
		}
		if (order == null) {
			// The order closed meanwhile (only possible after a crash): the items go back to the filler.
			try {
				store.saveNow(pending.listed().returned("Buy order closed before your sale"));
			} catch (IOException e) {
				MejtaMarket.log().error("Could not return the items of fill {}", pending.id(), e);
			}
			return;
		}
		boolean toBot = order.owner().equals(PriceBook.BOT);
		try {
			if (toBot) {
				store.finish(pending);
				ledger.boughtItems(pending.count());
			} else {
				store.saveNow(pending.filled());
			}
		} catch (IOException e) {
			MejtaMarket.log().error("Could not deliver fill {}; it is finished at {}'s next join", pending.id(), pending.sellerName(), e);
			return;
		}
		long proceeds = Fees.proceeds(pending.payAmount(), config.get(MarketSettings.SALES_TAX));
		pay(pending.payTo(), pending.sellerName(), proceeds, "market buy order #" + order.id());
		if (!toBot) {
			prices.record(order.itemId(), now, pending.payAmount(), pending.count(), order.owner(), pending.payTo());
			tell(order.owner(), Msg.success(pending.sellerName() + " sold you " + pending.count() + " x " + order.itemName()
					+ " for your buy order. Get them with /ah collect."));
		}
		tell(pending.payTo(), Msg.success("Sold " + pending.count() + " x " + order.itemName() + " to " + order.ownerName() + " for "
				+ money(proceeds) + (proceeds < pending.payAmount() ? " after tax" : "") + "."));
		trade("fill", pending.payTo(), pending.sellerName(), pending, pending.payAmount(), Map.of("order", order.id(), "owner", order.ownerName()));
		if (order.remaining() == 0) {
			closeOrder(order, "Filled");
		}
		saveState();
	}

	/** The market bot sells into a player's buy order: the items are created, the escrow leaves the economy. */
	public boolean botFill(BuyOrder order, int count) {
		long now = System.currentTimeMillis();
		if (order.state() != BuyOrder.State.OPEN || order.expired(now) || count <= 0 || count > order.remaining()
				|| order.owner().equals(PriceBook.BOT)) {
			return false;
		}
		Optional<Item> item = MarketItems.byId(order.itemId());
		if (item.isEmpty()) {
			return false;
		}
		JsonElement json;
		try {
			json = ItemJson.encode(registries(), new ItemStack(item.get()));
		} catch (RuntimeException e) {
			return false;
		}
		long pay = order.unitPrice() * count;
		Lot lot = Lot.botFill(store.newId(), json, order.itemId(), order.itemName(), order.category(), count, order.owner(), order.ownerName(),
				PriceBook.BOT, botName(), now, order.id(), pay);
		// The order pays first, then the items exist: a crash in between loses the delivery, never makes it twice.
		BuyOrder updated = order.withFill(lot.id(), count);
		try {
			store.saveNow(updated);
		} catch (IOException e) {
			return false;
		}
		try {
			store.saveNow(lot);
		} catch (IOException e) {
			MejtaMarket.log().error("The market bot could not deliver {} x {} to {}", count, order.itemId(), order.ownerName(), e);
		}
		ledger.useStock(order.itemId(), now, count);
		ledger.received(pay, count);
		tell(order.owner(), Msg.success(botName() + " sold you " + count + " x " + order.itemName() + " for your buy order. Get them with /ah collect."));
		trade("bot_fill", PriceBook.BOT, botName(), lot, pay, Map.of("order", order.id(), "owner", order.ownerName()));
		if (updated.remaining() == 0) {
			closeOrder(updated, "Filled");
		}
		saveState();
		return true;
	}

	// ---------------------------------------------------------------- joining

	/** At join: finishes or drops what a stop interrupted, and mentions the collection box. */
	public void onJoin(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (Lot lot : store.pendingOf(player.getUUID())) {
			boolean stillHeld = lot.before().entrySet().stream().allMatch(e -> e.getKey() < inventory.getContainerSize()
					&& ItemJson.matches(registries(), inventory.getItem(e.getKey()), e.getValue()));
			if (stillHeld) {
				store.discard(lot);
				MejtaMarket.log().info("Dropped unfinished market lot {}: {} still has the items (the change was not saved)", lot.id(), name(player));
			} else if (lot.state() == Lot.State.PENDING_LIST) {
				activateListing(lot);
				MejtaMarket.log().info("Completed listing {} of {}", lot.id(), name(player));
			} else {
				completeFill(lot);
				MejtaMarket.log().info("Completed buy order fill {} of {}", lot.id(), name(player));
			}
		}
		int waiting = waiting(player.getUUID());
		if (waiting > 0 && eco().isAvailable()) {
			player.sendSystemMessage(Msg.highlight("Market: ").append(Msg.info(waiting + " item(s) or payment(s) wait in your collection box. "))
					.append(Msg.button("Collect", "/ah collect", ChatFormatting.GREEN)));
		}
	}

	// ---------------------------------------------------------------- money and messages

	/** Pays a player; if that fails (e.g. a balance limit), the money waits in their collection box. */
	private void pay(UUID player, String playerName, long amount, String reason) {
		if (amount <= 0 || player.equals(PriceBook.BOT)) {
			return;
		}
		eco().ensureAccount(player, Optional.ofNullable(playerName));
		EconomyResult r = eco().deposit(player, amount, MejtaMarket.ID, reason);
		if (!r.success()) {
			MejtaMarket.log().warn("Could not pay {} {} ({}): {}; it waits in their collection box", playerName, amount, reason, r.status());
			owe(player, amount, reason);
		}
	}

	private void refund(UUID player, long amount, String reason) {
		if (amount > 0) {
			pay(player, null, amount, reason);
		}
	}

	private void owe(UUID player, long amount, String reason) {
		try {
			store.saveNow(new Payout(store.newId(), player, amount, reason, System.currentTimeMillis()));
		} catch (IOException e) {
			MejtaMarket.log().error("LOST PAYMENT: could not record {} owed to {} ({})", amount, player, reason, e);
			MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaMarket.ID, "market.payment_lost").actor(AuditActor.system(MejtaMarket.ID))
					.target(player, player.toString()).outcome(AuditOutcome.FAILED).detail("amount", amount).detail("reason", reason).build(),
					MarketPermissions.ADMIN);
		}
	}

	/** A message to a player if they are online. */
	private void tell(UUID player, Component message) {
		if (server == null || player.equals(PriceBook.BOT) || !config.get(MarketSettings.ANNOUNCE_SALES)) {
			return;
		}
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			online.sendSystemMessage(message);
		}
	}

	// ---------------------------------------------------------------- logs

	private void trade(String action, UUID actor, String actorName, Lot lot, long amount, Map<String, Object> extra) {
		JsonObject rec = record(action, actor, actorName, lot, lot.count());
		rec.addProperty("amount", amount);
		extra.forEach((k, v) -> rec.addProperty(k, String.valueOf(v)));
		tradeLog.append(rec);
		if (config.get(MarketSettings.AUDIT_TRADES) && !action.equals("bot_list")) {
			AuditEntry.Builder b = AuditEntry.builder(MejtaMarket.ID, "market." + action)
					.actor(actor.equals(PriceBook.BOT) ? AuditActor.system(MejtaMarket.ID) : AuditActor.player(actorName, actor))
					.target(lot.seller(), lot.sellerName()).outcome(AuditOutcome.SUCCESS)
					.detail("lot", lot.id()).detail("item", lot.itemId() + " x" + lot.count()).detail("amount", amount);
			extra.forEach(b::detail);
			MejtaCoreAPI.audit().record(b.build());
		}
	}

	private void orderLog(String action, UUID actor, String actorName, BuyOrder order, long amount, Map<String, Object> extra) {
		JsonObject rec = new JsonObject();
		rec.addProperty("action", action);
		rec.addProperty("actor", actor.toString());
		rec.addProperty("actor_name", actorName);
		rec.addProperty("order", order.id());
		rec.addProperty("item", order.itemId());
		rec.addProperty("amount_items", order.amount());
		rec.addProperty("unit_price", order.unitPrice());
		rec.addProperty("amount", amount);
		extra.forEach((k, v) -> rec.addProperty(k, String.valueOf(v)));
		tradeLog.append(rec);
		if (config.get(MarketSettings.AUDIT_TRADES)) {
			AuditEntry.Builder b = AuditEntry.builder(MejtaMarket.ID, "market." + action)
					.actor(actor.equals(PriceBook.BOT) ? AuditActor.system(MejtaMarket.ID) : AuditActor.player(actorName, actor))
					.target(order.owner(), order.ownerName()).outcome(AuditOutcome.SUCCESS)
					.detail("order", order.id()).detail("item", order.itemId() + " x" + order.amount()).detail("amount", amount);
			extra.forEach(b::detail);
			MejtaCoreAPI.audit().record(b.build());
		}
	}

	private static JsonObject record(String action, UUID actor, String actorName, Lot lot, int count) {
		JsonObject rec = new JsonObject();
		rec.addProperty("action", action);
		rec.addProperty("actor", actor.toString());
		rec.addProperty("actor_name", actorName);
		rec.addProperty("lot", lot.id());
		rec.addProperty("item", lot.itemId());
		rec.addProperty("count", count);
		rec.addProperty("seller", lot.seller().toString());
		return rec;
	}

	/** Recent player trades of an item, for {@code /ah price}. */
	public List<PriceBook.Trade> history(String itemId, int limit) {
		return new ArrayList<>(prices.recent(itemId, System.currentTimeMillis(), config.get(MarketSettings.PRICE_HISTORY_DAYS), limit));
	}
}
