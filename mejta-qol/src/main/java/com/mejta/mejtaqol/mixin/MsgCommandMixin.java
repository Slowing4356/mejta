package com.mejta.mejtaqol.mixin;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.chat.Replies;

/** Remembers who exchanged private messages, for {@code /r}. Changes nothing about the message. */
@Mixin(MsgCommand.class)
public abstract class MsgCommandMixin {
	@Inject(method = "sendMessage", at = @At("HEAD"))
	private static void mejtaqol$recordPartners(CommandSourceStack source, Collection<ServerPlayer> targets, PlayerChatMessage message,
			CallbackInfo ci) {
		Replies replies = MejtaQoL.repliesOrNull();
		ServerPlayer sender = source.getPlayer();
		if (replies == null || sender == null) {
			return;
		}
		for (ServerPlayer target : targets) {
			replies.record(sender.getUUID(), target.getUUID());
		}
	}
}
