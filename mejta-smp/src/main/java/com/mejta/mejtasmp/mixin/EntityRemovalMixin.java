package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import com.mejta.mejtasmp.drops.PileHolder;
import com.mejta.mejtasmp.drops.Piles;

/**
 * Where a pile's item entity is removed. Something took its visible stack (a player, hopper or mob) and
 * discarded it: the rest of the pile goes on as a new item entity. Falling out of the world destroys the
 * whole pile.
 */
@Mixin(Entity.class)
public abstract class EntityRemovalMixin {
	@Shadow
	private Entity.RemovalReason removalReason;

	@Inject(method = "setRemoved", at = @At("HEAD"))
	private void mejtasmp$continuePile(Entity.RemovalReason reason, CallbackInfo ci) {
		if (removalReason == null && reason == Entity.RemovalReason.DISCARDED && (Object) this instanceof ItemEntity item) {
			Piles.discarded(item);
		}
	}

	@Inject(method = "onBelowWorld", at = @At("HEAD"))
	private void mejtasmp$intoTheVoid(CallbackInfo ci) {
		if ((Object) this instanceof PileHolder pile) {
			pile.mejtasmp$setExtra(0);
		}
	}
}
