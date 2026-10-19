package com.mejta.mejtacorpse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacorpse.CorpseManager;
import com.mejta.mejtacorpse.MejtaCorpse;

/**
 * Dispensers never put armour on a corpse. Its armour is a display copy that MejtaCorpse replaces, so
 * a dispensed piece would be lost.
 */
@Mixin(LivingEntity.class)
public abstract class DispenserEquipMixin {
	@Inject(method = "canEquipWithDispenser", at = @At("HEAD"), cancellable = true)
	private void mejtacorpse$notOnCorpses(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		CorpseManager manager = MejtaCorpse.managerOrNull();
		if (manager != null && manager.isCorpseEntity((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
