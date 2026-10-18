package com.mejta.mejtautils.staffmode;

import java.util.List;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;

/** {@code /staffmode [on|off]}, {@code /staffmode restore confirm [player]}. */
public final class StaffModeCommands {
	private StaffModeCommands() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "staffmode", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.STAFFMODE))
				.executes(ctx -> toggle(ctx, null))
				.then(Commands.literal("on").executes(ctx -> toggle(ctx, true)))
				.then(Commands.literal("off").executes(ctx -> toggle(ctx, false)))
				.then(Commands.literal("restore")
						.then(Commands.literal("confirm")
								.executes(ctx -> confirm(ctx, ctx.getSource().getPlayerOrException()))
								.then(Commands.argument("player", EntityArgument.player())
										.requires(MejtaCoreAPI.requires(UtilsPermissions.STAFFMODE_RESTORE))
										.executes(ctx -> confirm(ctx, EntityArgument.getPlayer(ctx, "player")))))));
	}

	private static int toggle(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		StaffModeManager staff = MejtaUtils.staffMode();
		boolean enter = on != null ? on : !staff.isActive(player.getUUID());
		if (enter) {
			return staff.enter(player, MejtaCoreAPI.actor(player)) ? 1 : 0;
		}
		return switch (staff.exit(player, false, MejtaCoreAPI.actor(player))) {
			case DONE -> 1;
			case NOT_ACTIVE -> {
				ctx.getSource().sendFailure(Component.literal("You are not in staff mode."));
				yield 0;
			}
			case NEEDS_CONFIRMATION -> {
				List<String> problems = staff.confirmationNeeded(player.getUUID()).orElse(List.of());
				ctx.getSource().sendFailure(Component.literal("Restoring is not clearly safe: " + String.join("; ", problems)
						+ ". Run /staffmode restore confirm to restore anyway."));
				yield 0;
			}
			case FAILED -> 0;
		};
	}

	private static int confirm(CommandContext<CommandSourceStack> ctx, ServerPlayer target) throws CommandSyntaxException {
		StaffModeManager staff = MejtaUtils.staffMode();
		if (!staff.isActive(target.getUUID())) {
			ctx.getSource().sendFailure(Component.literal(target.getGameProfile().name() + " has no staff-mode session."));
			return 0;
		}
		if (staff.exit(target, true, MejtaCoreAPI.actor(ctx.getSource())) == StaffModeManager.ExitResult.DONE) {
			if (target != ctx.getSource().getPlayer()) {
				ctx.getSource().sendSuccess(() -> Component.literal("Restored " + target.getGameProfile().name() + "'s saved state.")
						.withStyle(ChatFormatting.AQUA), false);
			}
			return 1;
		}
		return 0;
	}
}
