package com.mejta.mejtamarket.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.menu.ServerMenus;
import com.mejta.mejtamarket.MarketPermissions;
import com.mejta.mejtamarket.MarketService;
import com.mejta.mejtamarket.MarketSettings;
import com.mejta.mejtamarket.data.BuyOrder;
import com.mejta.mejtamarket.data.Lot;
import com.mejta.mejtamarket.data.Payout;
import com.mejta.mejtamarket.logic.Categories;
import com.mejta.mejtamarket.logic.Durations;
import com.mejta.mejtamarket.logic.Fees;
import com.mejta.mejtamarket.logic.Search;
import com.mejta.mejtamarket.price.PriceBook;
import com.mejta.mejtamarket.util.ItemJson;
import com.mejta.mejtamarket.util.MarketItems;
import com.mejta.mejtamarket.util.Msg;

/**
 * The market's menus: the auction house, the collection box, your listings and buy orders. They are
 * server menus, so players without mods get chests and players with MejtaCore get screens. Nothing in
 * them can be taken; every click runs a {@link MarketService} transaction, which checks everything again.
 */
public final class MarketMenus {
	public enum Sort {
		NEWEST("Newest first"),
		CHEAPEST("Cheapest per item"),
		PRICIEST("Most expensive per item");

		private final String title;

		Sort(String title) {
			this.title = title;
		}

		public Sort next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** What a player is looking at; kept while they are online so the menu reopens where they left it. */
	public static final class View {
		Categories.Category category = Categories.Category.ALL;
		String search = "";
		Sort sort = Sort.NEWEST;
		int page;
		int orderPage;
		boolean ownOrders;
	}

	static final int PER_PAGE = 45;
	static final int PREV = 45;
	static final int CATEGORY = 46;
	static final int SEARCH = 47;
	static final int SORT = 48;
	static final int COLLECT = 49;
	static final int MY_LISTINGS = 50;
	static final int ORDERS = 51;
	static final int HELP = 52;
	static final int NEXT = 53;

	private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

	private final MarketService market;

	public MarketMenus(MarketService market) {
		this.market = market;
	}

	public View view(ServerPlayer player) {
		return VIEWS.computeIfAbsent(player.getUUID(), k -> new View());
	}

	public static void forget(UUID player) {
		VIEWS.remove(player);
	}

	private static boolean mayUse(ServerPlayer p) {
		return MejtaCoreAPI.hasPermission(p, MarketPermissions.USE);
	}

	private static String money(long amount) {
		return MarketService.money(amount);
	}

	// ---------------------------------------------------------------- browsing

	public void search(ServerPlayer player, String query) {
		View v = view(player);
		v.search = Search.clean(query);
		v.page = 0;
		browse(player);
	}

	List<Lot> shown(View v, long now) {
		List<Lot> lots = new ArrayList<>();
		for (Lot lot : market.store().listed()) {
			if (lot.expired(now) || !Search.matches(v.search, lot.itemId(), lot.itemName(), lot.sellerName())) {
				continue;
			}
			if (v.category != Categories.Category.ALL && !v.category.name().equals(lot.category())) {
				continue;
			}
			lots.add(lot);
		}
		Comparator<Lot> perItem = Comparator.comparingDouble(l -> (double) l.price() / l.count());
		switch (v.sort) {
			case CHEAPEST -> lots.sort(perItem);
			case PRICIEST -> lots.sort(perItem.reversed());
			default -> {
			}
		}
		return lots;
	}

	/** The auction house: listings on sale, filtered and sorted by the player's view. */
	public void browse(ServerPlayer player) {
		View v = view(player);
		long now = System.currentTimeMillis();
		List<Lot> lots = shown(v, now);
		int pages = Math.max(1, (lots.size() + PER_PAGE - 1) / PER_PAGE);
		v.page = Math.max(0, Math.min(v.page, pages - 1));
		boolean staff = MejtaCoreAPI.hasPermission(player, MarketPermissions.ADMIN);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Auction House" + (pages > 1 ? " (" + (v.page + 1) + "/" + pages + ")" : "")), 6);
		for (int i = 0; i < PER_PAGE && v.page * PER_PAGE + i < lots.size(); i++) {
			Lot lot = lots.get(v.page * PER_PAGE + i);
			menu.button(i, () -> lotIcon(player, lot, staff), click -> lotClicked(click, lot, staff));
		}
		if (v.page > 0) {
			menu.button(PREV, () -> MenuIcons.of(Items.ARROW, Component.literal("Previous page")), click -> {
				v.page--;
				browse(click.player());
			});
		}
		if (v.page < pages - 1) {
			menu.button(NEXT, () -> MenuIcons.of(Items.ARROW, Component.literal("Next page")), click -> {
				v.page++;
				browse(click.player());
			});
		}
		menu.button(CATEGORY, () -> MenuIcons.of(MarketItems.iconOf(v.category.icon()), Component.literal("Category: " + v.category.title())
				.withStyle(ChatFormatting.AQUA), MenuIcons.line("Left-click: next, right-click: previous")), click -> {
					v.category = click.type().isRight() ? v.category.previous() : v.category.next();
					v.page = 0;
					browse(click.player());
				});
		menu.button(SEARCH, () -> v.search.isEmpty()
				? MenuIcons.of(Items.OAK_SIGN, Component.literal("Search").withStyle(ChatFormatting.AQUA), MenuIcons.line("Click, then type what you look for"))
				: MenuIcons.of(Items.OAK_SIGN, Component.literal("Search: " + v.search).withStyle(ChatFormatting.AQUA),
						MenuIcons.line(lots.size() + " match(es)"), MenuIcons.line("Click to clear")), click -> {
					if (v.search.isEmpty()) {
						click.menu().close();
						click.player().sendSystemMessage(Msg.highlight("Search the market: ").append(Msg.suggest("type here", "/ah search ", ChatFormatting.AQUA)));
					} else {
						v.search = "";
						v.page = 0;
						browse(click.player());
					}
				});
		menu.button(SORT, () -> MenuIcons.of(Items.HOPPER, Component.literal("Sort: " + v.sort.title).withStyle(ChatFormatting.AQUA),
				MenuIcons.line("Click to change")), click -> {
					v.sort = v.sort.next();
					v.page = 0;
					browse(click.player());
				});
		menu.button(COLLECT, () -> {
			int waiting = market.waiting(player.getUUID());
			return MenuIcons.of(waiting > 0 ? Items.CHEST_MINECART : Items.MINECART, Component.literal("Collection box").withStyle(
					waiting > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY), MenuIcons.line(waiting > 0 ? waiting + " waiting" : "Empty"),
					MenuIcons.line("Bought, unsold and ordered items wait here"));
		}, click -> collection(click.player()));
		menu.button(MY_LISTINGS, () -> MenuIcons.of(Items.NAME_TAG, Component.literal("Your listings").withStyle(ChatFormatting.AQUA),
				MenuIcons.line(market.store().listedBy(player.getUUID()).size() + " of " + market.config().get(MarketSettings.LISTING_MAX) + " on sale")),
				click -> myListings(click.player()));
		if (market.config().get(MarketSettings.ORDERS_ENABLED)) {
			menu.button(ORDERS, () -> MenuIcons.of(Items.WRITABLE_BOOK, Component.literal("Buy orders").withStyle(ChatFormatting.AQUA),
					MenuIcons.line(market.store().openOrders().size() + " open"), MenuIcons.line("Sell to players who want your items")),
					click -> orders(click.player()));
		}
		menu.icon(HELP, () -> MenuIcons.of(Items.BOOK, Component.literal("How to sell").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Hold the item, then /ah sell <price> [amount]"),
				MenuIcons.line("Listing fee " + market.config().get(MarketSettings.LISTING_FEE) + "%, sales tax "
						+ market.config().get(MarketSettings.SALES_TAX) + "%"),
				MenuIcons.line("Buy orders: /ah order <amount> <price each> [item]"),
				MenuIcons.line("Market prices: /ah price [item]")));
		menu.validWhile(MarketMenus::mayUse);
		menu.open(player);
	}

	private ItemStack lotIcon(ServerPlayer viewer, Lot lot, boolean staff) {
		ItemStack base = market.server() == null ? ItemStack.EMPTY
				: ItemJson.decode(market.server().registryAccess(), lot.item()).orElse(ItemStack.EMPTY);
		if (base.isEmpty()) {
			base = MarketItems.paper();
		} else {
			base = base.copyWithCount(Math.max(1, Math.min(lot.count(), base.getMaxStackSize())));
		}
		boolean own = lot.seller().equals(viewer.getUUID());
		boolean bot = lot.seller().equals(PriceBook.BOT);
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Price: " + money(lot.price()) + (lot.count() > 1 ? " (" + money(lot.unitPrice()) + " each)" : ""))
				.withStyle(ChatFormatting.GOLD));
		lore.add(MenuIcons.line("Amount: " + lot.count()));
		lore.add(Component.literal("Seller: " + lot.sellerName()).withStyle(bot ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
		lore.add(MenuIcons.line("Ends in " + Durations.shortly(lot.expiresMillis() - System.currentTimeMillis())));
		OptionalLong ref = market.prices().lastReference(lot.itemId());
		if (ref.isPresent()) {
			lore.add(MenuIcons.line("Market price: " + money(ref.getAsLong()) + " each"));
		}
		lore.add(Component.literal(own ? "Click: cancel (items go to your collection box)" : "Click: buy").withStyle(own ? ChatFormatting.YELLOW
				: ChatFormatting.GREEN));
		if (staff && !own) {
			lore.add(Component.literal("Shift-right-click: remove (staff)").withStyle(ChatFormatting.RED));
		}
		return MenuIcons.of(base, Component.literal(lot.count() + " x " + lot.itemName()).withStyle(ChatFormatting.WHITE), lore);
	}

	private void lotClicked(MenuClick click, Lot lot, boolean staff) {
		ServerPlayer player = click.player();
		boolean own = lot.seller().equals(player.getUUID());
		if (staff && !own && click.type() == MenuClick.Type.SHIFT_RIGHT) {
			ServerMenus.confirm(player, Component.literal("Remove listing?"), List.of(MenuIcons.line(lot.count() + " x " + lot.itemName()
					+ " of " + lot.sellerName()), MenuIcons.line("The items go back to the seller's collection box")),
					() -> {
						if (MejtaCoreAPI.hasPermission(player, MarketPermissions.ADMIN)) {
							market.cancel(player, lot.id(), true);
						}
						browse(player);
					}, () -> browse(player));
			return;
		}
		if (click.type() != MenuClick.Type.LEFT && click.type() != MenuClick.Type.RIGHT) {
			return;
		}
		if (own) {
			ServerMenus.confirm(player, Component.literal("Cancel listing?"), List.of(MenuIcons.line(lot.count() + " x " + lot.itemName()),
					MenuIcons.line("The items go to your collection box"), MenuIcons.line("The listing fee is not refunded")),
					() -> {
						market.cancel(player, lot.id(), false);
						browse(player);
					}, () -> browse(player));
			return;
		}
		ServerMenus.confirm(player, Component.literal("Buy for " + money(lot.price()) + "?"), List.of(MenuIcons.line(lot.count() + " x "
				+ lot.itemName() + " from " + lot.sellerName()), MenuIcons.line("You have " + money(MejtaCoreAPI.economy().balance(player.getUUID())))),
				() -> {
					if (mayUse(player)) {
						market.buy(player, lot.id());
					}
					browse(player);
				}, () -> browse(player));
	}

	// ---------------------------------------------------------------- your listings

	public void myListings(ServerPlayer player) {
		List<Lot> lots = market.store().listedBy(player.getUUID());
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Your listings (" + lots.size() + ")"), 6);
		for (int i = 0; i < Math.min(PER_PAGE, lots.size()); i++) {
			Lot lot = lots.get(i);
			menu.button(i, () -> lotIcon(player, lot, false), click -> lotClicked(click, lot, false));
		}
		backButton(menu);
		menu.icon(HELP, () -> MenuIcons.of(Items.BOOK, Component.literal("Selling").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Hold the item, then /ah sell <price> [amount]"),
				MenuIcons.line("Unsold items return after " + market.config().get(MarketSettings.LISTING_HOURS) + " hours")));
		menu.validWhile(MarketMenus::mayUse);
		menu.open(player);
	}

	private void backButton(ServerMenu.Builder menu) {
		menu.button(PREV, () -> MenuIcons.of(Items.ARROW, Component.literal("Back to the market")), click -> browse(click.player()));
	}

	// ---------------------------------------------------------------- collection box

	public void collection(ServerPlayer player) {
		List<Payout> payouts = market.store().payoutsOf(player.getUUID());
		List<Lot> lots = market.store().collectable(player.getUUID());
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Collection box"), 6);
		int slot = 0;
		for (Payout p : payouts) {
			if (slot >= PER_PAGE) {
				break;
			}
			menu.button(slot++, () -> MenuIcons.of(Items.GOLD_NUGGET, Component.literal(money(p.amount())).withStyle(ChatFormatting.GOLD),
					MenuIcons.line(p.reason()), MenuIcons.line("Click: collect")), click -> {
						market.collectPayout(click.player(), p.id());
						collection(click.player());
					});
		}
		for (Lot lot : lots) {
			if (slot >= PER_PAGE) {
				break;
			}
			menu.button(slot++, () -> {
				ItemStack base = market.server() == null ? ItemStack.EMPTY
						: ItemJson.decode(market.server().registryAccess(), lot.item()).orElse(ItemStack.EMPTY);
				base = base.isEmpty() ? MarketItems.paper() : base.copyWithCount(Math.max(1, Math.min(lot.count(), base.getMaxStackSize())));
				return MenuIcons.of(base, Component.literal(lot.count() + " x " + lot.itemName()).withStyle(ChatFormatting.WHITE),
						List.of(MenuIcons.line(lot.note()), Component.literal("Click: take").withStyle(ChatFormatting.GREEN)));
			}, click -> {
				market.collect(click.player(), lot.id(), false);
				collection(click.player());
			});
		}
		backButton(menu);
		menu.button(COLLECT, () -> MenuIcons.of(Items.HOPPER, Component.literal("Collect all").withStyle(ChatFormatting.GREEN),
				MenuIcons.line((payouts.size() + lots.size()) + " waiting")), click -> {
					market.collectAll(click.player());
					collection(click.player());
				});
		menu.validWhile(MarketMenus::mayUse);
		menu.open(player);
	}

	// ---------------------------------------------------------------- buy orders

	public void orders(ServerPlayer player) {
		View v = view(player);
		long now = System.currentTimeMillis();
		List<BuyOrder> orders = market.store().openOrders().stream().filter(o -> !o.expired(now))
				.filter(o -> !v.ownOrders || o.owner().equals(player.getUUID()))
				.filter(o -> Search.matches(v.search, o.itemId(), o.itemName(), o.ownerName())).toList();
		int pages = Math.max(1, (orders.size() + PER_PAGE - 1) / PER_PAGE);
		v.orderPage = Math.max(0, Math.min(v.orderPage, pages - 1));
		boolean staff = MejtaCoreAPI.hasPermission(player, MarketPermissions.ADMIN);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Buy orders" + (pages > 1 ? " (" + (v.orderPage + 1) + "/" + pages + ")" : "")), 6);
		for (int i = 0; i < PER_PAGE && v.orderPage * PER_PAGE + i < orders.size(); i++) {
			BuyOrder order = orders.get(v.orderPage * PER_PAGE + i);
			menu.button(i, () -> orderIcon(player, order, staff), click -> orderClicked(click, order, staff));
		}
		backButton(menu);
		if (v.orderPage < pages - 1) {
			menu.button(NEXT, () -> MenuIcons.of(Items.ARROW, Component.literal("Next page")), click -> {
				v.orderPage++;
				orders(click.player());
			});
		}
		if (v.orderPage > 0) {
			menu.button(CATEGORY, () -> MenuIcons.of(Items.ARROW, Component.literal("Previous page")), click -> {
				v.orderPage--;
				orders(click.player());
			});
		}
		menu.button(SORT, () -> MenuIcons.of(v.ownOrders ? Items.PLAYER_HEAD : Items.WRITABLE_BOOK,
				Component.literal(v.ownOrders ? "Showing: your orders" : "Showing: all orders").withStyle(ChatFormatting.AQUA),
				MenuIcons.line("Click to switch")), click -> {
					v.ownOrders = !v.ownOrders;
					v.orderPage = 0;
					orders(click.player());
				});
		menu.icon(HELP, () -> MenuIcons.of(Items.BOOK, Component.literal("Buy orders").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Place one: /ah order <amount> <price each> [item]"),
				MenuIcons.line("(holding the item, you can leave out the item)"),
				MenuIcons.line("The money is set aside until the order is filled"),
				MenuIcons.line("Click an order to sell your plain items into it")));
		menu.validWhile(MarketMenus::mayUse);
		menu.open(player);
	}

	private ItemStack orderIcon(ServerPlayer viewer, BuyOrder order, boolean staff) {
		boolean own = order.owner().equals(viewer.getUUID());
		boolean bot = order.owner().equals(PriceBook.BOT);
		int have = MarketItems.countPlain(viewer.getInventory(), order.itemId());
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Pays " + money(order.unitPrice()) + " each").withStyle(ChatFormatting.GOLD));
		lore.add(MenuIcons.line("Wants " + order.remaining() + " more (of " + order.amount() + ")"));
		lore.add(Component.literal("Buyer: " + order.ownerName()).withStyle(bot ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
		lore.add(MenuIcons.line("Ends in " + Durations.shortly(order.expiresMillis() - System.currentTimeMillis())));
		if (own) {
			lore.add(Component.literal("Click: cancel (unspent money comes back)").withStyle(ChatFormatting.YELLOW));
		} else if (have > 0) {
			lore.add(Component.literal("You have " + have + ". Click: sell up to " + Math.min(have, order.remaining())).withStyle(ChatFormatting.GREEN));
		} else {
			lore.add(MenuIcons.line("You have none to sell (only plain items count)"));
		}
		if (staff && !own) {
			lore.add(Component.literal("Shift-right-click: remove (staff)").withStyle(ChatFormatting.RED));
		}
		return MenuIcons.of(new ItemStack(MarketItems.iconOf(order.itemId())), Component.literal(order.itemName()).withStyle(ChatFormatting.WHITE), lore);
	}

	private void orderClicked(MenuClick click, BuyOrder order, boolean staff) {
		ServerPlayer player = click.player();
		boolean own = order.owner().equals(player.getUUID());
		if (staff && !own && click.type() == MenuClick.Type.SHIFT_RIGHT) {
			ServerMenus.confirm(player, Component.literal("Remove buy order?"), List.of(MenuIcons.line(order.itemName() + " of " + order.ownerName()),
					MenuIcons.line("The unspent money goes back to them")), () -> {
						if (MejtaCoreAPI.hasPermission(player, MarketPermissions.ADMIN)) {
							market.cancelOrder(player, order.id(), true);
						}
						orders(player);
					}, () -> orders(player));
			return;
		}
		if (click.type() != MenuClick.Type.LEFT && click.type() != MenuClick.Type.RIGHT) {
			return;
		}
		if (own) {
			ServerMenus.confirm(player, Component.literal("Cancel buy order?"), List.of(MenuIcons.line(order.remaining() + " x " + order.itemName()
					+ " not yet delivered"), MenuIcons.line(money(order.escrow()) + " comes back to you")), () -> {
						market.cancelOrder(player, order.id(), false);
						orders(player);
					}, () -> orders(player));
			return;
		}
		int have = Math.min(MarketItems.countPlain(player.getInventory(), order.itemId()), order.remaining());
		if (have <= 0) {
			player.sendSystemMessage(Msg.error("You have no plain " + order.itemName() + " to sell."));
			return;
		}
		long pay = order.unitPrice() * have;
		long get = Fees.proceeds(pay, market.config().get(MarketSettings.SALES_TAX));
		ServerMenus.confirm(player, Component.literal("Sell " + have + " x " + order.itemName() + "?"), List.of(
				MenuIcons.line("To " + order.ownerName() + " for " + money(pay)), MenuIcons.line("You get " + money(get) + " after tax")), () -> {
					if (MejtaCoreAPI.hasPermission(player, MarketPermissions.ORDERS)) {
						market.fill(player, order.id(), have);
					} else {
						player.sendSystemMessage(Msg.error("You may not sell into buy orders."));
					}
					orders(player);
				}, () -> orders(player));
	}
}
