package com.mejta.mejtaqol.util;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;

/**
 * Finds online players by name for player commands, through MejtaCore's presence service: a player
 * hidden from the viewer (e.g. vanished staff) is neither suggested nor found, exactly as if offline.
 */
public final class Players {
	private Players() {
	}

	public static String name(ServerPlayer player) {
		return player.getGameProfile().name();
	}

	/** The online player called {@code name} (any case) whom {@code viewer} may see. */
	public static Optional<ServerPlayer> visible(MinecraftServer server, ServerPlayer viewer, String name) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (name(p).equalsIgnoreCase(name) && (viewer == null || MejtaCoreAPI.presence().canSee(viewer, p.getUUID()))) {
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}

	/** Suggests the names of online players the source may see, except the source itself. */
	public static CompletableFuture<Suggestions> suggestOthers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
		ServerPlayer self = ctx.getSource().getPlayer();
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
			if (p == self || (self != null && !MejtaCoreAPI.presence().canSee(self, p.getUUID()))) {
				continue;
			}
			if (name(p).toLowerCase(Locale.ROOT).startsWith(typed)) {
				builder.suggest(name(p));
			}
		}
		return builder.buildFuture();
	}
}
