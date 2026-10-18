package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: a vanished player with Bad Omen starting a raid in a village. */
@Mixin(targets = "net.minecraft.world.effect.BadOmenMobEffect")
public abstract class VanishBadOmenMixin {
	@WrapOperation(method = "applyEffectTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean mejtautils$noRaid(ServerPlayer player, Operation<Boolean> original) {
		return original.call(player) || VanishHooks.isVanished(player);
	}
}
