package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak path: advancement announcements in chat. The advancement is still granted. The announcement is
 * sent from a lambda inside {@code award}; its name ({@code lambda$award$0}) is the one Minecraft 26.1's
 * unobfuscated code uses. If a game update renames it, this injection is skipped ({@code require = 0})
 * and logged, and announcements are no longer hidden: the gametest reports it.
 */
@Mixin(PlayerAdvancements.class)
public abstract class VanishAdvancementMixin {
	@Shadow
	private ServerPlayer player;

	@WrapOperation(method = "lambda$award$0", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void mejtautils$hideAnnouncement(PlayerList list, Component message, boolean overlay, Operation<Void> original) {
		if (!VanishHooks.isVanished(player)) {
			original.call(list, message, overlay);
		}
	}
}
