package com.mejta.mejtamarket;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtamarket.bot.MarketBot;
import com.mejta.mejtamarket.menu.MarketMenus;
import com.mejta.mejtamarket.price.PriceBook;
import com.mejta.mejtamarket.price.PriceFile;
import com.mejta.mejtamarket.util.MarketItems;
import com.mejta.mejtamarket.util.Msg;

/**
 * {@code /ah} (also {@code /market}) for players, and {@code /mejtaadmin mejtamarket ...} for staff.
 */
public final class MarketCommands {
	private MarketCommands() {
	}

	public static void register(MarketService market, MarketMenus menus, MarketBot bot, PriceFile priceFile) {
		for (String name : List.of("ah", "market")) {
			MejtaCoreAPI.registerRootCommand(MejtaMarket.ID, name, (root, ctx) -> playerCommands(root, market, menus));
		}
		MejtaCoreAPI.registerServerCommands(MejtaMarket.ID, (root, ctx) -> root
				.then(Commands.literal("status").requires(MejtaCoreAPI.requires(MarketPermissions.ADMIN))
						.executes(c -> status(c.getSource(), market)))
				.then(Commands.literal("bot").requires(MejtaCoreAPI.requires(MarketPermissions.ADMIN))
						.executes(c -> botStats(c.getSource(), market))
						.then(Commands.literal("stats").executes(c -> botStats(c.getSource(), market)))
						.then(Commands.literal("run").executes(c -> botRun(c.getSource(), bot))))
				.then(Commands.literal("prices").requires(MejtaCoreAPI.requires(MarketPermissions.ADMIN))
						.executes(c -> priceListShow(c.getSource(), market))
						.then(Commands.literal("reload").executes(c -> priceReload(c.getSource(), market, priceFile)))
						.then(Commands.literal("set").then(Commands.argument("price", StringArgumentType.word())
								.executes(c -> priceSet(c, market, priceFile, null))
								.then(Commands.argument("item", StringArgumentType.greedyString()).suggests(MarketCommands::suggestItems)
										.executes(c -> priceSet(c, market, priceFile, StringArgumentType.getString(c, "item"))))))
						.then(Commands.literal("remove")
								.executes(c -> priceRemove(c, market, priceFile, null))
								.then(Commands.argument("item", StringArgumentType.greedyString()).suggests(MarketCommands::suggestItems)
										.executes(c -> priceRemove(c, market, priceFile, StringArgumentType.getString(c, "item"))))))
				.then(Commands.literal("remove").requires(MejtaCoreAPI.requires(MarketPermissions.ADMIN))
						.then(Commands.argument("id", StringArgumentType.word())
								.executes(c -> remove(c.getSource().getPlayerOrException(), market, StringArgumentType.getString(c, "id"))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> playerCommands(LiteralArgumentBuilder<CommandSourceStack> root, MarketService market,
			MarketMenus menus) {
		return root.requires(MejtaCoreAPI.requires(MarketPermissions.USE))
				.executes(c -> open(c.getSource().getPlayerOrException(), market, menus))
				.then(Commands.literal("help").executes(c -> help(c.getSource().getPlayerOrException())))
				.then(Commands.literal("search").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					if (refuse(p, market)) {
						return 0;
					}
					menus.askSearch(p);
					return 1;
				}).then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							if (refuse(p, market)) {
								return 0;
							}
							menus.search(p, StringArgumentType.getString(c, "text"));
							return 1;
						})))
				.then(Commands.literal("sell").requires(MejtaCoreAPI.requires(MarketPermissions.SELL))
						.executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							if (refuse(p, market)) {
								return 0;
							}
							if (p.getMainHandItem().isEmpty()) {
								p.sendSystemMessage(Msg.error("Hold the items you want to sell in your main hand."));
								return 0;
							}
							menus.askSellPrice(p);
							return 1;
						})
						.then(Commands.argument("price", StringArgumentType.word())
								.executes(c -> sell(c.getSource().getPlayerOrException(), market, StringArgumentType.getString(c, "price"), 0))
								.then(Commands.argument("amount", IntegerArgumentType.integer(1, 10_000))
										.executes(c -> sell(c.getSource().getPlayerOrException(), market, StringArgumentType.getString(c, "price"),
												IntegerArgumentType.getInteger(c, "amount"))))))
				.then(Commands.literal("collect").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					if (!MejtaCoreAPI.economy().isAvailable()) {
						p.sendSystemMessage(Msg.error(market.closedReason().orElse("The market is unavailable.")));
						return 0;
					}
					menus.collection(p);
					return 1;
				}))
				.then(Commands.literal("listings").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					if (refuse(p, market)) {
						return 0;
					}
					menus.myListings(p);
					return 1;
				}))
				.then(Commands.literal("orders").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					if (refuse(p, market)) {
						return 0;
					}
					menus.orders(p);
					return 1;
				}))
				.then(Commands.literal("order").requires(MejtaCoreAPI.requires(MarketPermissions.ORDERS))
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 100_000))
								.then(Commands.argument("price", StringArgumentType.word())
										.executes(c -> order(c, market, null))
										.then(Commands.argument("item", StringArgumentType.greedyString()).suggests(MarketCommands::suggestItems)
												.executes(c -> order(c, market, StringArgumentType.getString(c, "item")))))))
				.then(Commands.literal("price")
						.executes(c -> price(c.getSource().getPlayerOrException(), market, null))
						.then(Commands.argument("item", StringArgumentType.greedyString()).suggests(MarketCommands::suggestItems)
								.executes(c -> price(c.getSource().getPlayerOrException(), market, StringArgumentType.getString(c, "item")))));
	}

	private static boolean refuse(ServerPlayer player, MarketService market) {
		Optional<String> why = market.closedReason();
		why.ifPresent(w -> player.sendSystemMessage(Msg.error(w)));
		return why.isPresent();
	}

	private static int open(ServerPlayer player, MarketService market, MarketMenus menus) {
		if (refuse(player, market)) {
			return 0;
		}
		menus.browse(player);
		return 1;
	}

	private static int help(ServerPlayer player) {
		player.sendSystemMessage(Msg.highlight("Auction house"));
		for (String[] line : new String[][] {
				{"/ah", "browse what is on sale"},
				{"/ah search [text]", "find items (without text: type on a sign)"},
				{"/ah sell [price] [amount]", "sell what you hold, for that price in all (without a price: type it on a sign)"},
				{"/ah order <amount> <price each> [item]", "ask to buy items (holding one, leave out the item)"},
				{"/ah orders", "buy orders you can sell into"},
				{"/ah collect", "your collection box"},
				{"/ah listings", "your listings"},
				{"/ah price [item]", "what an item sells for"}}) {
			player.sendSystemMessage(Msg.suggest(line[0], line[0].replaceAll(" [<\\[].*", " "), ChatFormatting.AQUA)
					.append(Msg.info(" " + line[1])));
		}
		return 1;
	}

	private static int sell(ServerPlayer player, MarketService market, String priceText, int amount) {
		OptionalLong price = MejtaCoreAPI.economy().parse(priceText);
		if (price.isEmpty()) {
			player.sendSystemMessage(Msg.error("\"" + priceText + "\" is not a price. Examples: 50, 12.5, 2k"));
			return 0;
		}
		return market.list(player, price.getAsLong(), amount).isPresent() ? 1 : 0;
	}

	private static int order(CommandContext<CommandSourceStack> c, MarketService market, String itemText) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		String priceText = StringArgumentType.getString(c, "price");
		OptionalLong price = MejtaCoreAPI.economy().parse(priceText);
		if (price.isEmpty()) {
			player.sendSystemMessage(Msg.error("\"" + priceText + "\" is not a price. Examples: 5, 0.5, 1k"));
			return 0;
		}
		Optional<Item> item = itemText == null ? held(player) : MarketItems.byId(itemText);
		if (item.isEmpty()) {
			player.sendSystemMessage(Msg.error(itemText == null ? "Hold the item you want, or name it: /ah order <amount> <price each> <item>"
					: "There is no item called " + itemText + "."));
			return 0;
		}
		return market.placeOrder(player, item.get(), IntegerArgumentType.getInteger(c, "amount"), price.getAsLong()).isPresent() ? 1 : 0;
	}

	private static Optional<Item> held(ServerPlayer player) {
		ItemStack s = player.getMainHandItem();
		return s.isEmpty() ? Optional.empty() : Optional.of(s.getItem());
	}

	private static int price(ServerPlayer player, MarketService market, String itemText) {
		Optional<Item> item = itemText == null ? held(player) : MarketItems.byId(itemText);
		if (item.isEmpty()) {
			player.sendSystemMessage(Msg.error(itemText == null ? "Hold an item, or name it: /ah price <item>" : "There is no item called " + itemText + "."));
			return 0;
		}
		if (!MejtaCoreAPI.economy().isAvailable()) {
			player.sendSystemMessage(Msg.error(market.closedReason().orElse("")));
			return 0;
		}
		String id = MarketItems.id(item.get());
		String name = new ItemStack(item.get()).getHoverName().getString();
		OptionalLong ref = market.reference(id);
		MutableComponent head = Msg.highlight(name + ": ");
		head.append(ref.isPresent() ? Msg.info("market price " + MarketService.money(ref.getAsLong()) + " each") : Msg.info("no market price yet"));
		player.sendSystemMessage(head);
		List<PriceBook.Trade> trades = market.history(id, 5);
		if (trades.isEmpty()) {
			player.sendSystemMessage(Msg.info("No recent player trades."));
		}
		for (PriceBook.Trade t : trades) {
			player.sendSystemMessage(Msg.info(" " + MarketService.money(t.unitPrice()) + " each, "
					+ com.mejta.mejtamarket.logic.Durations.shortly(System.currentTimeMillis() - t.millis()) + " ago"));
		}
		return 1;
	}

	private static CompletableFuture<Suggestions> suggestItems(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		String typed = b.getRemaining().toLowerCase(Locale.ROOT);
		int n = 0;
		for (Identifier key : BuiltInRegistries.ITEM.keySet()) {
			String full = key.toString();
			String shortName = "minecraft".equals(key.getNamespace()) ? key.getPath() : full;
			if (shortName.startsWith(typed) || full.startsWith(typed)) {
				b.suggest(shortName);
				if (++n >= 100) {
					break;
				}
			}
		}
		return b.buildFuture();
	}

	// ---------------------------------------------------------------- staff

	private static int status(CommandSourceStack source, MarketService market) {
		long now = System.currentTimeMillis();
		long listings = market.store().listed().stream().filter(l -> !l.expired(now)).count();
		long botListings = market.store().listedBy(PriceBook.BOT).size();
		int orders = market.store().openOrders().size();
		source.sendSystemMessage(Component.literal("MejtaMarket: " + listings + " listing(s) (" + botListings + " by the bot), " + orders
				+ " open buy order(s), " + market.priceList().size() + " item(s) on the price list; economy "
				+ (MejtaCoreAPI.economy().isAvailable() ? MejtaCoreAPI.economy().providerId().orElse("?") : "missing")).withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int botStats(CommandSourceStack source, MarketService market) {
		long now = System.currentTimeMillis();
		long budget = market.ledger().available(now, MarketService.whole(market.config().get(MarketSettings.BOT_DAILY_BUDGET)),
				MarketService.whole(market.config().get(MarketSettings.BOT_BUDGET_CAP)));
		long net = market.ledger().absorbed() - market.ledger().paidOut();
		source.sendSystemMessage(Component.literal(market.botName() + (market.config().get(MarketSettings.BOT_ENABLED) ? "" : " (off)") + ": budget "
				+ MarketService.money(budget) + ", paid players " + MarketService.money(market.ledger().paidOut()) + " for "
				+ market.ledger().itemsBought() + " item(s), received " + MarketService.money(market.ledger().absorbed()) + " for "
				+ market.ledger().itemsSold() + " item(s). Net: " + (net >= 0 ? MarketService.money(net) + " taken out of" : MarketService.money(-net)
						+ " added to") + " the economy.").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int botRun(CommandSourceStack source, MarketBot bot) {
		Optional<String> did = bot.act();
		source.sendSystemMessage(Component.literal(did.map(d -> "The bot " + d + ".").orElse("The bot found nothing it may do right now."))
				.withStyle(ChatFormatting.GOLD));
		return did.isPresent() ? 1 : 0;
	}

	private static int priceListShow(CommandSourceStack source, MarketService market) {
		StringBuilder b = new StringBuilder();
		for (String id : market.priceList().items()) {
			if (b.length() > 0) {
				b.append(", ");
			}
			OptionalLong ref = market.prices().lastReference(id);
			b.append(id.startsWith("minecraft:") ? id.substring(10) : id).append(' ').append(market.priceList().wholeUnits(id).toPlainString());
			if (ref.isPresent()) {
				b.append(" (now ").append(MarketService.money(ref.getAsLong())).append(')');
			}
		}
		source.sendSystemMessage(Component.literal("Price list (" + market.priceList().size() + "): " + b).withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int priceReload(CommandSourceStack source, MarketService market, PriceFile file) {
		market.setPriceList(file.load());
		source.sendSystemMessage(Component.literal("Reloaded " + market.priceList().size() + " price(s) from " + file.path().getFileName() + ".")
				.withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static Optional<Item> itemFor(CommandSourceStack source, String text) {
		if (text != null) {
			return MarketItems.byId(text);
		}
		return source.getPlayer() == null ? Optional.empty() : held(source.getPlayer());
	}

	private static int priceSet(CommandContext<CommandSourceStack> c, MarketService market, PriceFile file, String itemText) {
		CommandSourceStack source = c.getSource();
		Optional<Item> item = itemFor(source, itemText);
		String priceText = StringArgumentType.getString(c, "price");
		BigDecimal price;
		try {
			price = new BigDecimal(priceText);
		} catch (NumberFormatException e) {
			price = BigDecimal.ZERO;
		}
		if (item.isEmpty() || price.signum() <= 0) {
			source.sendSystemMessage(Component.literal("Usage: /mejtaadmin mejtamarket prices set <price in whole units> [item] (or hold the item)")
					.withStyle(ChatFormatting.RED));
			return 0;
		}
		String id = MarketItems.id(item.get());
		market.priceList().set(id, price);
		file.save(market.priceList());
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaMarket.ID, "market.price_set").actor(MejtaCoreAPI.actor(source)).target(id)
				.outcome(AuditOutcome.SUCCESS).detail("price", price.toPlainString()).build());
		source.sendSystemMessage(Component.literal("Price list: " + id + " = " + price.toPlainString()
				+ ". The market price moves towards it by at most the daily limit.").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int priceRemove(CommandContext<CommandSourceStack> c, MarketService market, PriceFile file, String itemText) {
		CommandSourceStack source = c.getSource();
		Optional<Item> item = itemFor(source, itemText);
		if (item.isEmpty() || !market.priceList().remove(MarketItems.id(item.get()))) {
			source.sendSystemMessage(Component.literal("That item is not on the price list.").withStyle(ChatFormatting.RED));
			return 0;
		}
		String id = MarketItems.id(item.get());
		file.save(market.priceList());
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaMarket.ID, "market.price_remove").actor(MejtaCoreAPI.actor(source)).target(id)
				.outcome(AuditOutcome.SUCCESS).build());
		source.sendSystemMessage(Component.literal("Removed " + id + " from the price list; the bot no longer trades it.").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int remove(ServerPlayer staff, MarketService market, String id) {
		if (market.store().lot(id).isPresent()) {
			return market.cancel(staff, id, true) ? 1 : 0;
		}
		if (market.store().order(id).isPresent()) {
			return market.cancelOrder(staff, id, true) ? 1 : 0;
		}
		staff.sendSystemMessage(Msg.error("No listing or buy order " + id + "."));
		return 0;
	}
}
