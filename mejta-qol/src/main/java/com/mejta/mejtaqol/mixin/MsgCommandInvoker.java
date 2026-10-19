package com.mejta.mejtaqol.mixin;

import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;

/** Lets {@code /r} send through the game's own private-message code. */
@Mixin(MsgCommand.class)
public interface MsgCommandInvoker {
	@Invoker("sendMessage")
	static void mejtaqol$sendMessage(CommandSourceStack source, Collection<ServerPlayer> targets, PlayerChatMessage message) {
		throw new AssertionError("mixin not applied");
	}
}
