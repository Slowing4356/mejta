package com.mejta.mejtaqol.tpa;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.tpa.TpaRequests.Direction;
import com.mejta.mejtaqol.tpa.TpaRequests.Request;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Players;

/** {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny}, {@code /tpacancel}, {@code /tptoggle}. */
public final class TpaCommands {
	private TpaCommands() {
	}

	public static void register() {
		String id = MejtaQoL.ID;
		MejtaCoreAPI.registerRootCommand(id, "tpa", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(Players::suggestOthers)
						.executes(c -> send(c, Direction.TO_TARGET))));
		MejtaCoreAPI.registerRootCommand(id, "tpahere", (root, ctx) -> root
				.requires(s -> Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA).test(s)
						&& Cmds.available(QolSettings.TPAHERE_ENABLED, QolPermissions.TPAHERE).test(s))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(Players::suggestOthers)
						.executes(c -> send(c, Direction.TO_SENDER))));
		MejtaCoreAPI.registerRootCommand(id, "tpaccept", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA))
				.executes(c -> MejtaQoL.tpa().accept(c.getSource().getPlayerOrException(), Optional.empty()) ? 1 : 0)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(TpaCommands::suggestSenders)
						.executes(c -> MejtaQoL.tpa().accept(c.getSource().getPlayerOrException(),
								Optional.of(StringArgumentType.getString(c, "player"))) ? 1 : 0)));
		MejtaCoreAPI.registerRootCommand(id, "tpdeny", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA))
				.executes(c -> MejtaQoL.tpa().deny(c.getSource().getPlayerOrException(), Optional.empty()) ? 1 : 0)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(TpaCommands::suggestSenders)
						.executes(c -> MejtaQoL.tpa().deny(c.getSource().getPlayerOrException(),
								Optional.of(StringArgumentType.getString(c, "player"))) ? 1 : 0)));
		MejtaCoreAPI.registerRootCommand(id, "tpacancel", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA))
				.executes(c -> MejtaQoL.tpa().cancel(c.getSource().getPlayerOrException(), Optional.empty()))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(TpaCommands::suggestTargets)
						.executes(c -> MejtaQoL.tpa().cancel(c.getSource().getPlayerOrException(),
								Optional.of(StringArgumentType.getString(c, "player"))))));
		MejtaCoreAPI.registerRootCommand(id, "tptoggle", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TPA_ENABLED, QolPermissions.TPA))
				.executes(c -> MejtaQoL.tpa().toggle(c.getSource().getPlayerOrException()) ? 1 : 0));
	}

	private static int send(CommandContext<CommandSourceStack> c, Direction direction) throws CommandSyntaxException {
		ServerPlayer sender = c.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(c, "player");
		Optional<ServerPlayer> target = Players.visible(c.getSource().getServer(), sender, name);
		if (target.isEmpty()) {
			sender.sendSystemMessage(Msg.error("No player called " + name + " is online."));
			return 0;
		}
		return MejtaQoL.tpa().send(sender, target.get(), direction) ? 1 : 0;
	}

	private static CompletableFuture<Suggestions> suggestSenders(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		ServerPlayer self = c.getSource().getPlayer();
		if (self != null && MejtaQoL.tpa() != null) {
			String typed = b.getRemaining().toLowerCase(Locale.ROOT);
			for (Request r : MejtaQoL.tpa().requests().incoming(self.getUUID())) {
				if (r.senderName().toLowerCase(Locale.ROOT).startsWith(typed)) {
					b.suggest(r.senderName());
				}
			}
		}
		return b.buildFuture();
	}

	private static CompletableFuture<Suggestions> suggestTargets(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		ServerPlayer self = c.getSource().getPlayer();
		if (self != null && MejtaQoL.tpa() != null) {
			String typed = b.getRemaining().toLowerCase(Locale.ROOT);
			for (Request r : MejtaQoL.tpa().requests().outgoing(self.getUUID())) {
				if (r.targetName().toLowerCase(Locale.ROOT).startsWith(typed)) {
					b.suggest(r.targetName());
				}
			}
		}
		return b.buildFuture();
	}
}
