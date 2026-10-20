package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;

import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpSettings;

/** {@code villagers.infinite_trading}: a trade does not use up the offer (villagers and wandering traders). */
@Mixin(AbstractVillager.class)
public abstract class VillagerTradeMixin {
	@WrapOperation(method = "notifyTrade", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/trading/MerchantOffer;increaseUses()V"))
	private void mejtasmp$infinite(MerchantOffer offer, Operation<Void> original) {
		var config = MejtaSMP.configOrNull();
		if (config == null || !config.get(SmpSettings.INFINITE_TRADING)) {
			original.call(offer);
		}
	}
}
