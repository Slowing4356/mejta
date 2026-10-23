package com.mejta.mejtamarket.test;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtamarket.MarketService;
import com.mejta.mejtamarket.MejtaMarket;
import com.mejta.mejtamarket.data.BuyOrder;
import com.mejta.mejtamarket.data.Lot;
import com.mejta.mejtamarket.util.MarketItems;

/**
 * Server game test: a headless server with MejtaCore, MejtaMarket and a test economy. Fake players list,
 * buy, place and fill buy orders, and the bot buys a cheap listing; after each step every item and coin is
 * where it belongs, exactly once. Fake players cannot be saved by the game, so the market uses a no-op save.
 */
public class MejtaMarketGameTest {
	private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-00000000a001");
	private static final UUID BUYER = UUID.fromString("00000000-0000-4000-8000-00000000a002");

	@GameTest(maxTicks = 20)
	public void market(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MarketService market = MejtaMarket.market();
		market.setSaver(p -> {
		});
		FakePlayer seller = FakePlayer.get(level, new GameProfile(SELLER, "market_seller"));
		FakePlayer buyer = FakePlayer.get(level, new GameProfile(BUYER, "market_buyer"));
		seller.getInventory().clearContent();
		buyer.getInventory().clearContent();
		TestEconomy.BALANCES.put(SELLER, 0L);
		TestEconomy.BALANCES.put(BUYER, 100_000L);
		helper.assertTrue(market.closedReason().isEmpty(), Component.literal("market open with the test economy"));

		// Listing: 10 of 16 diamonds, for 50.00. The listing fee (1 %) is paid by the seller.
		TestEconomy.BALANCES.put(SELLER, 50L);
		Inventory sInv = seller.getInventory();
		sInv.setItem(0, new ItemStack(Items.DIAMOND, 16));
		Optional<Lot> listed = market.list(seller, 5_000, 10);
		helper.assertTrue(listed.isPresent(), Component.literal("listed"));
		helper.assertValueEqual(sInv.getItem(0).getCount(), 6, Component.literal("10 diamonds left the inventory"));
		helper.assertValueEqual(listed.get().state(), Lot.State.LISTED, Component.literal("listing is on sale"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(SELLER), 0L, Component.literal("listing fee 0.50 paid"));
		helper.assertTrue(market.store().raw().read("lots/" + listed.get().id()).isPresent(), Component.literal("listing on disk"));

		// Buying: the buyer pays 50.00, gets the diamonds; the seller gets 47.50 (5 % tax). A second buy finds nothing.
		helper.assertValueEqual(market.buy(buyer, listed.get().id()), MarketService.BuyResult.BOUGHT, Component.literal("bought"));
		helper.assertValueEqual(MarketItems.countPlain(buyer.getInventory(), "minecraft:diamond"), 10, Component.literal("buyer has 10 diamonds"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(BUYER), 95_000L, Component.literal("buyer paid 50.00"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(SELLER), 4_750L, Component.literal("seller got 47.50 after tax"));
		helper.assertTrue(market.store().lot(listed.get().id()).isEmpty(), Component.literal("lot gone after collecting"));
		helper.assertValueEqual(market.buy(buyer, listed.get().id()), MarketService.BuyResult.GONE, Component.literal("cannot buy twice"));

		// A failed save puts the items back and lists nothing.
		market.setSaver(p -> {
			throw new IllegalStateException("test: save fails");
		});
		int before = market.store().listed().size();
		helper.assertTrue(market.list(seller, 1_000, 6).isEmpty(), Component.literal("listing refused when the save fails"));
		helper.assertValueEqual(sInv.getItem(0).getCount(), 6, Component.literal("items back after a failed save"));
		helper.assertValueEqual(market.store().listed().size(), before, Component.literal("nothing listed after a failed save"));
		market.setSaver(p -> {
		});

		// Buy order: 32 cobblestone at 1.00, filled by the seller from 40; the buyer collects them.
		Optional<BuyOrder> order = market.placeOrder(buyer, Items.COBBLESTONE, 32, 100);
		helper.assertTrue(order.isPresent(), Component.literal("order placed"));
		long afterOrder = TestEconomy.BALANCES.get(BUYER);
		helper.assertValueEqual(afterOrder, 95_000L - 3_200L - 32L, Component.literal("32.00 set aside plus 0.32 fee"));
		sInv.setItem(9, new ItemStack(Items.COBBLESTONE, 40));
		helper.assertValueEqual(market.fill(seller, order.get().id(), 64), MarketService.FillResult.FILLED, Component.literal("filled"));
		helper.assertValueEqual(MarketItems.countPlain(sInv, "minecraft:cobblestone"), 8, Component.literal("32 cobblestone left the filler"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(SELLER), 4_750L + 3_040L, Component.literal("filler got 30.40 after tax"));
		helper.assertTrue(market.store().order(order.get().id()).isEmpty(), Component.literal("filled order closed"));
		helper.assertValueEqual(market.collectAll(buyer), 32, Component.literal("buyer collected 32"));
		helper.assertValueEqual(MarketItems.countPlain(buyer.getInventory(), "minecraft:cobblestone"), 32, Component.literal("buyer has 32 cobblestone"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(BUYER), afterOrder, Component.literal("nothing refunded from a filled order"));

		// The bot buys a cheap diamond (price list 100.00, bot pays up to 75 %), paying from its budget.
		long paidBefore = market.ledger().paidOut();
		long sellerBefore = TestEconomy.BALANCES.get(SELLER);
		Optional<Lot> cheap = market.list(seller, 1_000, 1);
		helper.assertTrue(cheap.isPresent(), Component.literal("cheap diamond listed"));
		helper.assertTrue(market.botBuy(cheap.get()), Component.literal("bot bought the cheap diamond"));
		helper.assertValueEqual(market.ledger().paidOut() - paidBefore, 1_000L, Component.literal("bot paid 10.00 from its budget"));
		helper.assertValueEqual(TestEconomy.BALANCES.get(SELLER), sellerBefore - 10L + 950L, Component.literal("seller paid by the bot"));
		helper.assertTrue(market.store().lot(cheap.get().id()).isEmpty(), Component.literal("bot's purchase destroyed"));

		// A search for a price-list item nobody sells counts as interest, and the bot plans to bring some.
		Optional<String> coming = MejtaMarket.bot().searched(buyer, "oak log");
		helper.assertValueEqual(coming.orElse("none"), "minecraft:oak_log", Component.literal("bot plans to list searched oak logs"));
		helper.assertTrue(market.demand().of("minecraft:oak_log", System.currentTimeMillis()) >= 1.0, Component.literal("search counted as demand"));
		helper.succeed();
	}
}
