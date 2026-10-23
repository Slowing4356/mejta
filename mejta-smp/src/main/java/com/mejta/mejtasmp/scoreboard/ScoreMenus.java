package com.mejta.mejtasmp.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpPermissions;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.SmpSettings.SidebarView;
import com.mejta.mejtasmp.stats.PlayerStats;
import com.mejta.mejtasmp.stats.Stat;

/** {@code /sb}: pick your sidebar. {@code /leaderboard} ({@code /lb}): the top players. Chest menus with the shared MejtaCore look (filler panes, Close at the bottom). */
public final class ScoreMenus {
	static final int[] RANK_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
	private static final int[] STAT_SLOTS = {46, 47, 48, 50, 51};
	private static final int SLOT_YOU = 49;
	private static final int SLOT_CLOSE = 53;

	private ScoreMenus() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaSMP.ID, "sb", (root, ctx) -> root
				.requires(source -> MejtaSMP.config().get(SmpSettings.SIDEBAR_ENABLED) && MejtaCoreAPI.hasPermission(source, SmpPermissions.SIDEBAR))
				.executes(c -> openSidebarMenu(c.getSource().getPlayerOrException()) ? 1 : 0)
				.then(Commands.argument("view", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("off", "stats", "kills", "playtime"), b))
						.executes(c -> {
							ServerPlayer player = c.getSource().getPlayerOrException();
							SidebarView view = switch (StringArgumentType.getString(c, "view").toLowerCase(Locale.ROOT)) {
								case "off" -> SidebarView.OFF;
								case "stats" -> SidebarView.MY_STATS;
								case "kills" -> SidebarView.TOP_KILLS;
								case "playtime" -> SidebarView.TOP_PLAYTIME;
								default -> null;
							};
							if (view == null) {
								player.sendSystemMessage(Component.literal("Use off, stats, kills or playtime.").withStyle(ChatFormatting.RED));
								return 0;
							}
							MejtaSMP.scoreboards().setView(player, view);
							player.sendSystemMessage(Component.literal("Sidebar: " + viewName(view)).withStyle(ChatFormatting.GREEN));
							return 1;
						})));
		for (String name : new String[] {"leaderboard", "lb"}) {
			MejtaCoreAPI.registerRootCommand(MejtaSMP.ID, name, (root, ctx) -> root
					.requires(source -> MejtaSMP.config().get(SmpSettings.LEADERBOARD) && MejtaCoreAPI.hasPermission(source, SmpPermissions.LEADERBOARD))
					.executes(c -> openLeaderboard(c.getSource().getPlayerOrException(), Stat.KILLS) ? 1 : 0));
		}
	}

	// ---------------------------------------------------------------- /sb

	public static boolean openSidebarMenu(ServerPlayer player) {
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Sidebar"), 3);
		option(menu, player, 10, SidebarView.OFF, Items.BARRIER, "Nothing on the side of the screen");
		option(menu, player, 12, SidebarView.MY_STATS, Items.BOOK, "Your kills, deaths, K/D, kill streak, mob kills and play time");
		option(menu, player, 14, SidebarView.TOP_KILLS, Items.IRON_SWORD, "The 10 players with the most kills");
		option(menu, player, 16, SidebarView.TOP_PLAYTIME, Items.CLOCK, "The 10 players who played the longest");
		menu.closeButton(22).fillEmpty();
		return menu.validWhile(p -> MejtaSMP.config().get(SmpSettings.SIDEBAR_ENABLED) && MejtaCoreAPI.hasPermission(p, SmpPermissions.SIDEBAR))
				.open(player);
	}

	private static void option(ServerMenu.Builder menu, ServerPlayer player, int slot, SidebarView view, Item item, String description) {
		menu.button(slot, () -> {
			boolean selected = MejtaSMP.scoreboards().view(player.getUUID()) == view;
			ItemStack stack = MenuIcons.of(item, Component.literal(viewName(view)).withStyle(selected ? ChatFormatting.GREEN : ChatFormatting.AQUA),
					List.of(MenuIcons.line(description), Component.empty(),
							Component.literal(selected ? "Showing now" : "Click to show").withStyle(ChatFormatting.DARK_GRAY)));
			if (selected) {
				stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
			}
			return stack;
		}, click -> {
			MejtaSMP.scoreboards().setView(click.player(), view);
			click.menu().refresh();
		});
	}

	static String viewName(SidebarView view) {
		return switch (view) {
			case OFF -> "Off";
			case MY_STATS -> "Your stats";
			case TOP_KILLS -> "Top kills";
			case TOP_PLAYTIME -> "Top play time";
		};
	}

	// ---------------------------------------------------------------- /leaderboard

	public static boolean openLeaderboard(ServerPlayer player, Stat stat) {
		List<PlayerStats> top = MejtaSMP.scoreboards().book().top(stat, RANK_SLOTS.length);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Leaderboard: " + stat.label()), 6);
		for (int i = 0; i < top.size(); i++) {
			PlayerStats s = top.get(i);
			int rank = i + 1;
			menu.icon(RANK_SLOTS[i], () -> head(player, s, Component.literal("#" + rank + " " + s.name()).withStyle(rankColour(rank)),
					List.of(Component.literal(stat.label() + ": ").withStyle(ChatFormatting.GRAY)
							.append(Component.literal(stat.format(s)).withStyle(ChatFormatting.WHITE)))));
		}
		if (top.isEmpty()) {
			menu.icon(22, () -> MenuIcons.of(Items.PAPER, Component.literal("Nobody yet").withStyle(ChatFormatting.GRAY)));
		}
		Stat[] stats = Stat.values();
		Item[] icons = {Items.IRON_SWORD, Items.SKELETON_SKULL, Items.CLOCK, Items.ROTTEN_FLESH, Items.BLAZE_POWDER};
		for (int i = 0; i < stats.length && i < STAT_SLOTS.length; i++) {
			Stat option = stats[i];
			Item icon = icons[i];
			menu.button(STAT_SLOTS[i], () -> {
				ItemStack stack = MenuIcons.of(icon, Component.literal(option.label()).withStyle(option == stat ? ChatFormatting.GREEN : ChatFormatting.AQUA),
						List.of(Component.literal(option == stat ? "Showing" : "Click to show").withStyle(ChatFormatting.DARK_GRAY)));
				if (option == stat) {
					stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
				}
				return stack;
			}, click -> openLeaderboard(click.player(), option));
		}
		menu.icon(SLOT_YOU, () -> {
			PlayerStats me = MejtaSMP.scoreboards().book().get(player.getUUID())
					.orElse(PlayerStats.empty(player.getUUID(), player.getGameProfile().name()));
			int rank = rankOf(stat, me);
			return head(player, me, Component.literal("You").withStyle(ChatFormatting.YELLOW), List.of(
					Component.literal(stat.label() + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(stat.format(me)).withStyle(ChatFormatting.WHITE)),
					MenuIcons.line(rank > 0 ? "Rank #" + rank : "Not ranked yet")));
		});
		menu.closeButton(SLOT_CLOSE).fillEmpty();
		return menu.validWhile(p -> MejtaSMP.config().get(SmpSettings.LEADERBOARD) && MejtaCoreAPI.hasPermission(p, SmpPermissions.LEADERBOARD))
				.refreshEvery(40)
				.open(player);
	}

	private static int rankOf(Stat stat, PlayerStats me) {
		if (stat.of(me) <= 0) {
			return 0;
		}
		List<PlayerStats> all = MejtaSMP.scoreboards().book().top(stat, Integer.MAX_VALUE);
		return all.indexOf(MejtaSMP.scoreboards().book().get(me.id()).orElse(me)) + 1;
	}

	/** A head with the player's skin if they are online (their profile carries it), else the default. */
	private static ItemStack head(ServerPlayer viewer, PlayerStats s, Component name, List<Component> lore) {
		ServerPlayer online = viewer.level().getServer().getPlayerList().getPlayer(s.id());
		GameProfile profile = online != null ? online.getGameProfile() : new GameProfile(s.id(), s.name().length() <= 16 ? s.name() : s.name().substring(0, 16));
		return MenuIcons.head(profile, name, new ArrayList<>(lore));
	}

	private static ChatFormatting rankColour(int rank) {
		return switch (rank) {
			case 1 -> ChatFormatting.GOLD;
			case 2 -> ChatFormatting.WHITE;
			case 3 -> ChatFormatting.RED;
			default -> ChatFormatting.GRAY;
		};
	}
}
