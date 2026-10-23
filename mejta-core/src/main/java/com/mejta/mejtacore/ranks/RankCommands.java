package com.mejta.mejtacore.ranks;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.rank.Rank;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/** {@code /mejtaadmin rank}: list, get, set and clear players' ranks. Works for offline players. */
public final class RankCommands {
	private static final SuggestionProvider<CommandSourceStack> RANKS = (ctx, builder) -> {
		for (Rank rank : Rank.values()) {
			builder.suggest(rank.id());
		}
		return builder.buildFuture();
	};

	private RankCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("rank")
				.requires(MejtaCoreAPI.requires(PermissionRegistry.RANKS_MANAGE))
				.executes(RankCommands::usage)
				.then(Commands.literal("list").executes(RankCommands::list))
				.then(Commands.literal("get")
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(RankCommands::get)))
				.then(Commands.literal("set")
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.then(Commands.argument("rank", StringArgumentType.word()).suggests(RANKS)
										.executes(RankCommands::set))))
				.then(Commands.literal("clear")
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(RankCommands::clear)));
	}

	private static int usage(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("Rank commands (ranks: member, bronze, silver, gold):").withStyle(ChatFormatting.GOLD));
		line(source, "/mejtaadmin rank list", "every player with a stored rank");
		line(source, "/mejtaadmin rank get <player>", "a player's rank and how they got it");
		line(source, "/mejtaadmin rank set <player> <rank>", "set it; works offline, e.g. from a donation store or crate reward");
		line(source, "/mejtaadmin rank clear <player>", "back to Member; playtime may give Bronze again");
		int hours = Ranks.get().playtimeHours();
		source.sendSystemMessage(Component.literal(hours > 0
				? " Members earn Bronze after " + hours + " hours of playtime (ranks.playtime_hours)."
				: " Bronze is not earned by playtime (ranks.playtime_hours = 0).").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		Map<UUID, RankRecord> all = Ranks.get().all();
		long ranked = all.values().stream().filter(r -> r.rank() != Rank.MEMBER).count();
		source.sendSystemMessage(Component.literal(ranked + " player(s) with a rank:").withStyle(ChatFormatting.GOLD));
		for (Rank rank : new Rank[] { Rank.GOLD, Rank.SILVER, Rank.BRONZE }) {
			for (RankRecord record : all.values()) {
				if (record.rank() == rank) {
					source.sendSystemMessage(Component.literal(" ").append(Ranks.get().prefix(rank)).append(" ")
							.append(Component.literal(record.name()).withStyle(ChatFormatting.WHITE))
							.append(Component.literal("  " + how(record)).withStyle(ChatFormatting.DARK_GRAY)));
				}
			}
		}
		return (int) ranked;
	}

	private static int get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		for (NameAndId profile : profiles(ctx)) {
			Rank rank = Ranks.get().rank(profile.id());
			String detail = Ranks.get().record(profile.id()).map(RankCommands::how).orElse("nothing stored");
			source.sendSystemMessage(Component.literal(profile.name() + ": ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(rank.displayName()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  (" + detail + ")").withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		String typed = StringArgumentType.getString(ctx, "rank");
		Rank rank = Rank.byId(typed).orElse(null);
		if (rank == null) {
			source.sendFailure(Component.literal("Unknown rank '" + typed + "'; use member, bronze, silver or gold"));
			return 0;
		}
		int count = 0;
		for (NameAndId profile : profiles(ctx)) {
			Ranks.get().set(source.getServer(), MejtaCoreAPI.actor(source), profile.id(), profile.name(), rank, RankRecord.STAFF);
			ServerPlayer online = source.getServer().getPlayerList().getPlayer(profile.id());
			if (online != null) {
				online.sendSystemMessage(rank == Rank.MEMBER
						? Component.literal("Your rank is now Member.").withStyle(ChatFormatting.GRAY)
						: Component.literal("Your rank is now ").withStyle(ChatFormatting.GREEN).append(Ranks.get().prefix(rank))
								.append(Component.literal(". Thank you for supporting the server!").withStyle(ChatFormatting.GREEN)));
			}
			String name = profile.name();
			source.sendSuccess(() -> Component.literal(name + " is now " + rank.displayName()
					+ (online == null ? " (offline: applies when they join)" : "") + "."), false);
			count++;
		}
		return count;
	}

	private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		int count = 0;
		for (NameAndId profile : profiles(ctx)) {
			Ranks.get().clear(source.getServer(), MejtaCoreAPI.actor(source), profile.id(), profile.name());
			String name = profile.name();
			source.sendSuccess(() -> Component.literal(name + " is a Member again; nothing is stored for them."), false);
			count++;
		}
		return count;
	}

	private static Collection<NameAndId> profiles(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return GameProfileArgument.getGameProfiles(ctx, "player");
	}

	private static String how(RankRecord record) {
		String when = record.sinceMillis() > 0 ? " since " + new SimpleDateFormat("yyyy-MM-dd").format(new Date(record.sinceMillis())) : "";
		return (RankRecord.PLAYTIME.equals(record.source()) ? "earned by playtime" : "set by staff") + when;
	}

	private static void line(CommandSourceStack source, String command, String description) {
		source.sendSystemMessage(Component.literal(" " + command).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)));
	}
}
