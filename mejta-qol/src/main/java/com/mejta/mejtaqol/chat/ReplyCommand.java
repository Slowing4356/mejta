package com.mejta.mejtaqol.chat;

import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.mixin.MsgCommandInvoker;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;

/**
 * {@code /r <message>} and {@code /reply <message>}: a private message to the last player you exchanged
 * one with (through {@code /msg}, {@code /tell}, {@code /w} or {@code /r}). The message goes through the
 * game's own {@code /msg} code, so it is signed, filtered and shown exactly like {@code /msg}.
 */
public final class ReplyCommand {
	private ReplyCommand() {
	}

	public static void register() {
		for (String name : new String[] {"r", "reply"}) {
			MejtaCoreAPI.registerRootCommand(MejtaQoL.ID, name, (root, ctx) -> root
					.requires(Cmds.available(QolSettings.REPLY_ENABLED, QolPermissions.REPLY))
					.then(Commands.argument("message", MessageArgument.message()).executes(ReplyCommand::reply)));
		}
	}

	private static int reply(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer sender = c.getSource().getPlayerOrException();
		Optional<Replies.Partner> partner = MejtaQoL.replies().partner(sender.getUUID());
		ServerPlayer target = partner.map(p -> c.getSource().getServer().getPlayerList().getPlayer(p.partner())).orElse(null);
		// Someone you wrote to who has since become hidden (e.g. vanished) counts as offline; someone who
		// wrote to you can always be answered.
		if (target == null || (!partner.get().incoming() && !MejtaCoreAPI.presence().canSee(sender, target.getUUID()))) {
			sender.sendSystemMessage(Msg.error(partner.isEmpty() ? "Nobody has messaged you yet." : "That player is no longer online."));
			return 0;
		}
		MessageArgument.resolveChatMessage(c, "message", message -> MsgCommandInvoker.mejtaqol$sendMessage(c.getSource(), List.of(target), message));
		return 1;
	}
}
