package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtautils.freeze.FreezeHooks;

/** Frozen players may only run the commands in freeze.allowed_commands. Every command passes through here. */
@Mixin(Commands.class)
public abstract class FreezeCommandMixin {
	@Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
	private void mejtautils$blockWhileFrozen(CommandSourceStack source, String command, CallbackInfo ci) {
		ServerPlayer player = source.getPlayer();
		if (player != null && FreezeHooks.commandBlocked(player, command)) {
			player.sendSystemMessage(Component.literal("You cannot use that command while frozen.").withStyle(ChatFormatting.RED));
			ci.cancel();
		}
	}
}
