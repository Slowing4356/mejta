package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak paths: entity tracking (a vanished player's projectiles, fishing hooks...) and sounds an entity
 * plays for everyone around it (footsteps, eating, armour).
 */
@Mixin(Entity.class)
public abstract class VanishEntityMixin {
	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void mejtautils$hideFromTracker(ServerPlayer observer, CallbackInfoReturnable<Boolean> cir) {
		if (!VanishHooks.canSeeActor(observer, (Entity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
	private void mejtautils$silence(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		if (VanishHooks.filtersSounds() && VanishHooks.isVanishedActor((Entity) (Object) this)) {
			ci.cancel();
		}
	}
}
