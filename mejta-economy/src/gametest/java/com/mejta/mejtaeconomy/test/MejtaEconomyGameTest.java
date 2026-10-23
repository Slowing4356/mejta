package com.mejta.mejtaeconomy.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.economy.EconomyService;
import com.mejta.mejtaeconomy.MejtaEconomy;

/**
 * Server game test: on a headless server with MejtaCore and MejtaEconomy, money moves through MejtaCore's
 * economy API as other addons use it, the listeners see it and the commands exist.
 */
public class MejtaEconomyGameTest {
	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000ec0a1");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000ec0a2");

	@GameTest(maxTicks = 20)
	public void economy(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		EconomyService eco = MejtaCoreAPI.economy();
		helper.assertTrue(eco.isAvailable(), Component.literal("economy available"));
		helper.assertValueEqual(eco.providerId(), Optional.of(MejtaEconomy.ID), Component.literal("provider"));
		for (String name : List.of("balance", "bal", "pay", "baltop", "eco")) {
			helper.assertTrue(server.getCommands().getDispatcher().getRoot().getChild(name) != null, Component.literal("/" + name + " is registered"));
		}

		List<String> changes = new ArrayList<>();
		eco.addListener((player, oldBalance, newBalance, source, reason) -> changes.add(player + ":" + oldBalance + ">" + newBalance));

		helper.assertTrue(eco.ensureAccount(A, Optional.of("eco_a")), Component.literal("account A"));
		long startA = eco.balance(A);
		helper.assertValueEqual(eco.parse("12.5").orElse(-1), 1250L, Component.literal("parse"));
		helper.assertValueEqual(eco.format(1250), "$12.50", Component.literal("format"));

		// Offline deposit creates B's account.
		helper.assertTrue(eco.deposit(B, 1000, "test", "offline sale").success(), Component.literal("deposit to offline B"));
		helper.assertTrue(eco.hasAccount(B), Component.literal("B has an account"));
		helper.assertValueEqual(eco.withdraw(A, startA + 1, "test", "too much").status(), EconomyResult.Status.INSUFFICIENT_FUNDS,
				Component.literal("no overdraft"));

		long before = eco.balance(A) + eco.balance(B);
		EconomyResult paid = eco.transfer(B, A, 400, "test", "purchase");
		helper.assertTrue(paid.success(), Component.literal("transfer"));
		helper.assertValueEqual(eco.balance(A) + eco.balance(B), before, Component.literal("transfer keeps the total"));
		helper.assertValueEqual(eco.transfer(B, A, 100_000, "test", "too much").status(), EconomyResult.Status.INSUFFICIENT_FUNDS,
				Component.literal("transfer refused"));
		helper.assertValueEqual(eco.balance(A) + eco.balance(B), before, Component.literal("refused transfer changes nothing"));
		helper.assertTrue(eco.top(100).stream().anyMatch(e -> e.player().equals(B)), Component.literal("B in top"));
		helper.assertValueEqual(changes.size(), 3, Component.literal("listener saw deposit and both sides of the transfer: " + changes));

		// Clean up so a rerun starts the same way.
		eco.withdraw(A, eco.balance(A) - startA, "test", "cleanup");
		eco.withdraw(B, eco.balance(B), "test", "cleanup");
		helper.succeed();
	}
}
