package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak paths: entity tracking of the player itself (it overrides Entity's method) and death messages. */
@Mixin(ServerPlayer.class)
public abstract class VanishServerPlayerMixin {
	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void mejtautils$hideFromTracker(ServerPlayer observer, CallbackInfoReturnable<Boolean> cir) {
		if (!VanishHooks.canSee(observer, (ServerPlayer) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@WrapOperation(method = "die", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void mejtautils$hideDeathMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original) {
		if (!VanishHooks.isVanished((ServerPlayer) (Object) this)) {
			original.call(list, message, overlay);
		}
	}
}
