package com.mejta.mejtautils.freeze;

import java.util.Map;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.util.Targets.Target;
import com.mejta.mejtautils.util.UtilsAudit;

/** {@code /freeze <player> [reason]}, {@code /unfreeze <player>}, {@code /freeze list}, {@code /freeze info <player>}. */
public final class FreezeCommands {
	private FreezeCommands() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "freeze", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.FREEZE))
				.then(Commands.literal("list")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.FREEZE_INFO))
						.executes(FreezeCommands::list))
				.then(Commands.literal("info")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.FREEZE_INFO))
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(FreezeCommands::info)))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> freeze(ctx, ""))
						.then(Commands.argument("reason", StringArgumentType.greedyString())
								.executes(ctx -> freeze(ctx, StringArgumentType.getString(ctx, "reason"))))));
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "unfreeze", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.FREEZE))
				.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(FreezeCommands::unfreeze)));
	}

	private static int freeze(CommandContext<CommandSourceStack> ctx, String reason) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Target target = Targets.single(ctx, "player");
		String why = reason.length() > 200 ? reason.substring(0, 200) : reason;
		FreezeManager freeze = MejtaUtils.freeze();
		if (freeze.isFrozen(target.id())) {
			source.sendFailure(Component.literal(target.name() + " is already frozen."));
			return 0;
		}
		ServerPlayer self = source.getPlayer();
		if (self != null && self.getUUID().equals(target.id())) {
			source.sendFailure(Component.literal("You cannot freeze yourself."));
			return 0;
		}
		boolean exempt = MejtaUtils.config().get(UtilsSettings.OP_BYPASS) && target.online()
				.map(p -> MejtaCoreAPI.hasPermission(p, UtilsPermissions.FREEZE_EXEMPT))
				.orElseGet(() -> MejtaCoreAPI.hasPermission(source.getServer(), target.profile(), UtilsPermissions.FREEZE_EXEMPT));
		if (exempt || !Targets.mayActOn(source, target)) {
			source.sendFailure(Component.literal(target.name() + " cannot be frozen (" + (exempt ? UtilsPermissions.FREEZE_EXEMPT
					: UtilsPermissions.PROTECTED).node() + ")."));
			UtilsAudit.record("freeze.start", source, target, AuditOutcome.DENIED, Map.of("reason", exempt ? "exempt" : "protected"));
			return 0;
		}
		freeze.freeze(target.id(), target.name(), target.online().orElse(null), MejtaCoreAPI.actor(source), why);
		source.sendSuccess(() -> Component.literal("Froze " + target.name() + (target.online().isEmpty() ? " (offline: frozen when they join)" : "")
				+ ".").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int unfreeze(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Target target = Targets.single(ctx, "player");
		if (!MejtaUtils.freeze().unfreeze(target.id(), MejtaCoreAPI.actor(source), source.getServer())) {
			source.sendFailure(Component.literal(target.name() + " is not frozen."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Unfroze " + target.name() + ".").withStyle(ChatFormatting.AQUA), false);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		var all = MejtaUtils.freeze().all();
		source.sendSystemMessage(Component.literal(all.size() + " frozen player(s):").withStyle(ChatFormatting.GOLD));
		for (FreezeManager.Frozen f : all) {
			boolean online = source.getServer().getPlayerList().getPlayer(f.player()) != null;
			source.sendSystemMessage(Component.literal(" " + f.name() + (online ? "" : " (offline)") + " by " + f.byName() + " since " + f.since()
					+ (f.reason().isEmpty() ? "" : ": " + f.reason())).withStyle(ChatFormatting.GRAY));
		}
		return all.size();
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Target target = Targets.single(ctx, "player");
		var record = MejtaUtils.freeze().record(target.id());
		if (record.isEmpty()) {
			source.sendFailure(Component.literal(target.name() + " is not frozen."));
			return 0;
		}
		FreezeManager.Frozen f = record.get();
		source.sendSystemMessage(Component.literal(f.name() + ": frozen by " + f.byName() + " since " + f.since()).withStyle(ChatFormatting.GOLD));
		if (!f.reason().isEmpty()) {
			source.sendSystemMessage(Component.literal(" Reason: " + f.reason()).withStyle(ChatFormatting.GRAY));
		}
		target.online().ifPresentOrElse(
				p -> source.sendSystemMessage(Component.literal(" Online, ping " + p.connection.latency() + " ms").withStyle(ChatFormatting.GRAY)),
				() -> source.sendSystemMessage(Component.literal(" Offline (frozen again when they join)").withStyle(ChatFormatting.GRAY)));
		Map<String, Integer> blocked = MejtaUtils.freeze().blockedCounts(target.id());
		source.sendSystemMessage(Component.literal(" Blocked since freeze: " + (blocked.isEmpty() ? "nothing" : blocked)).withStyle(ChatFormatting.GRAY));
		source.sendSystemMessage(Component.literal(" Details: mejta-data/mejtautils/freeze.jsonl").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}
}
