package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: the Warden targeting a vanished player (it sniffs and listens, not only sees). */
@Mixin(Warden.class)
public abstract class VanishWardenMixin {
	@Inject(method = "canTargetEntity", cancellable = true,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/warden/Warden;isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z"))
	private void mejtautils$ignoreVanished(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (VanishHooks.isVanished(entity)) {
			cir.setReturnValue(false);
		}
	}
}
