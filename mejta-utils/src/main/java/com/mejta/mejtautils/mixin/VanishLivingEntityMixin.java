package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.LivingEntity;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak paths: mob targeting and aggro (every targeting check asks {@code canBeSeenByAnyone}, which also
 * covers tempting, golems and piglins) and being pushed by mobs.
 */
@Mixin(LivingEntity.class)
public abstract class VanishLivingEntityMixin {
	@Inject(method = "canBeSeenByAnyone", at = @At("HEAD"), cancellable = true)
	private void mejtautils$hideFromMobs(CallbackInfoReturnable<Boolean> cir) {
		if (VanishHooks.hiddenFromMobs((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@WrapOperation(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isSpectator()Z"))
	private boolean mejtautils$notPushable(LivingEntity entity, Operation<Boolean> original) {
		return original.call(entity) || VanishHooks.isVanished(entity);
	}
}
