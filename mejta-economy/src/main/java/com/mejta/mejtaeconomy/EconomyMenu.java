package com.mejta.mejtaeconomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.economy.EconomyService;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtaeconomy.EconomyCommands.AdminAction;
import com.mejta.mejtaeconomy.data.Ledger;

/**
 * {@code /eco menu}: staff edit balances without typing commands. A list of every account (online players
 * first), then a page per player with give and take buttons, set, reset and history. Every change goes the
 * same way as {@code /eco give|take|set}: audited, logged and reported in chat.
 */
final class EconomyMenu {
	private static final int PER_PAGE = 45;
	/** Whole currency units of the give and take buttons. */
	private static final long[] STEPS = {1, 10, 100, 1_000, 10_000};
	private static final int[] GIVE_SLOTS = {11, 12, 13, 14, 15};
	private static final int[] TAKE_SLOTS = {20, 21, 22, 23, 24};

	private EconomyMenu() {
	}

	private static boolean allowed(ServerPlayer player) {
		return MejtaCoreAPI.hasPermission(player, EconomyPermissions.ADMIN) && MejtaEconomy.provider().ledger().isPresent();
	}

	// ---------------------------------------------------------------- list of accounts

	static int openList(ServerPlayer viewer, int page) {
		if (!EconomyCommands.ready(viewer.createCommandSourceStack())) {
			return 0;
		}
		Ledger ledger = MejtaEconomy.provider().ledger().orElseThrow();
		List<Ledger.Account> accounts = new ArrayList<>(ledger.ranking());
		accounts.sort(Comparator.comparing((Ledger.Account a) -> viewer.level().getServer().getPlayerList().getPlayer(a.id()) == null));
		int pages = Math.max(1, (accounts.size() + PER_PAGE - 1) / PER_PAGE);
		int p = Math.max(0, Math.min(page, pages - 1));
		EconomyService eco = MejtaCoreAPI.economy();

		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Balances (" + accounts.size() + ")"), 6)
				.validWhile(EconomyMenu::allowed);
		for (int i = 0; i < PER_PAGE && p * PER_PAGE + i < accounts.size(); i++) {
			Ledger.Account a = accounts.get(p * PER_PAGE + i);
			NameAndId who = new NameAndId(a.id(), a.name());
			boolean online = viewer.level().getServer().getPlayerList().getPlayer(a.id()) != null;
			menu.button(i, () -> MenuIcons.head(profile(who), Component.literal(who.name()).withStyle(online ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					List.of(MenuIcons.line("Balance: " + eco.format(eco.balance(who.id()))),
							MenuIcons.line(online ? "Online" : "Offline"),
							Component.literal("Click to edit").withStyle(ChatFormatting.YELLOW))),
					click -> openPlayer(click.player(), who, p));
		}
		if (p > 0) {
			menu.button(45, () -> MenuIcons.page(false, p, pages), click -> openList(click.player(), p - 1));
		}
		menu.icon(49, () -> MenuIcons.of(Items.GOLD_INGOT, Component.literal("Economy").withStyle(ChatFormatting.GOLD),
				MenuIcons.line(ledger.size() + " account(s)"),
				MenuIcons.line(eco.format(ledger.total()) + " in circulation"),
				MenuIcons.line("Players who never joined: /eco menu <name>")));
		menu.closeButton(50);
		if (p < pages - 1) {
			menu.button(53, () -> MenuIcons.page(true, p + 2, pages), click -> openList(click.player(), p + 1));
		}
		menu.fillEmpty().refreshEvery(20).open(viewer);
		return 1;
	}

	// ---------------------------------------------------------------- one player

	static int openPlayer(ServerPlayer viewer, NameAndId target, int fromPage) {
		if (!EconomyCommands.ready(viewer.createCommandSourceStack())) {
			return 0;
		}
		EconomyService eco = MejtaCoreAPI.economy();
		long unit = pow10(eco.fractionDigits());

		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Balance of " + target.name()), 4)
				.validWhile(EconomyMenu::allowed);
		menu.icon(4, () -> MenuIcons.head(profile(target), Component.literal(target.name()).withStyle(ChatFormatting.GOLD),
				List.of(MenuIcons.line("Balance: " + eco.format(eco.balance(target.id()))),
						MenuIcons.line("Changes are audited and show in /eco history"))));
		for (int i = 0; i < STEPS.length; i++) {
			long amount = STEPS[i] * unit;
			int count = i + 1;
			menu.button(GIVE_SLOTS[i], () -> icon(Items.EMERALD, count, "Give " + eco.format(amount), ChatFormatting.GREEN),
					click -> {
						EconomyCommands.change(click.player().createCommandSourceStack(), target, AdminAction.GIVE, amount);
						click.menu().refresh();
					});
			menu.button(TAKE_SLOTS[i], () -> icon(Items.REDSTONE, count, "Take " + eco.format(amount), ChatFormatting.RED),
					click -> {
						EconomyCommands.change(click.player().createCommandSourceStack(), target, AdminAction.TAKE, amount);
						click.menu().refresh();
					});
		}
		menu.button(29, () -> MenuIcons.of(Items.NAME_TAG, Component.literal("Set an exact amount").withStyle(ChatFormatting.AQUA),
				MenuIcons.line("Closes the menu and puts"),
				MenuIcons.line("/eco set " + target.name() + " … in chat")),
				click -> {
					click.menu().close();
					suggest(click.player(), "/eco set " + target.name() + " ", "Click here to type " + target.name() + "'s new balance");
				});
		menu.button(31, () -> MenuIcons.of(Items.TNT, Component.literal("Reset to " + eco.format(0)).withStyle(ChatFormatting.RED),
				MenuIcons.line("Shift-click to set the balance to 0")),
				click -> {
					if (click.type().isShift()) {
						EconomyCommands.change(click.player().createCommandSourceStack(), target, AdminAction.SET, 0);
						click.menu().refresh();
					} else {
						click.player().sendSystemMessage(Component.literal("Shift-click to reset " + target.name() + "'s balance.").withStyle(ChatFormatting.GRAY));
					}
				});
		menu.button(33, () -> MenuIcons.of(Items.BOOK, Component.literal("History").withStyle(ChatFormatting.YELLOW),
				MenuIcons.line("Shows the last transactions in chat")),
				click -> {
					click.menu().close();
					EconomyCommands.history(click.player().createCommandSourceStack(), target.id(), target.name());
				});
		menu.backButton(27, "balances", player -> openList(player, fromPage));
		menu.closeButton(35);
		menu.fillEmpty().refreshEvery(20).open(viewer);
		return 1;
	}

	// ---------------------------------------------------------------- helpers

	private static ItemStack icon(Item item, int count, String name, ChatFormatting color) {
		ItemStack stack = MenuIcons.of(item, Component.literal(name).withStyle(color));
		stack.setCount(count);
		return stack;
	}

	private static GameProfile profile(NameAndId who) {
		String name = who.name().length() <= 16 ? who.name() : who.name().substring(0, 16);
		return new GameProfile(who.id(), name);
	}

	private static void suggest(ServerPlayer player, String command, String text) {
		player.sendSystemMessage(Component.literal("[MejtaEconomy] ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(text).withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.UNDERLINE)
						.withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(command))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))))));
	}

	private static long pow10(int digits) {
		long v = 1;
		for (int i = 0; i < digits; i++) {
			v *= 10;
		}
		return v;
	}
}
