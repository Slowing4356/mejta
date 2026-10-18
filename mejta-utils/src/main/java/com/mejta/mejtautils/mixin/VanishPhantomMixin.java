package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: phantoms spawning around a vanished player who has not slept. */
@Mixin(PhantomSpawner.class)
public abstract class VanishPhantomMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean mejtautils$noPhantoms(ServerPlayer player, Operation<Boolean> original) {
		return original.call(player) || VanishHooks.isVanished(player);
	}
}
