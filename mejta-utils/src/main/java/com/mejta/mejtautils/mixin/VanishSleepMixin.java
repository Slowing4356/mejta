package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: sleeping. Vanished staff count like spectators, so they never block (or reveal) a night skip. */
@Mixin(SleepStatus.class)
public abstract class VanishSleepMixin {
	@WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean mejtautils$ignoreVanished(ServerPlayer player, Operation<Boolean> original) {
		return original.call(player) || VanishHooks.isVanished(player);
	}
}
