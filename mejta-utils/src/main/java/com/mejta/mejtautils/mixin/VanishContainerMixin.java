package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Silent containers: a vanished player (with the setting on) opens chests, trapped chests, barrels,
 * shulker boxes and ender chests without the lid animation, sound, redstone signal or game event. The
 * menu is the normal one on the same container, so nothing is copied.
 */
@Mixin({BarrelBlockEntity.class, ChestBlockEntity.class, PlayerEnderChestContainer.class, ShulkerBoxBlockEntity.class})
public abstract class VanishContainerMixin {
	@Inject(method = "startOpen", at = @At("HEAD"), cancellable = true)
	private void mejtautils$silentOpen(ContainerUser user, CallbackInfo ci) {
		if (user instanceof Entity entity && VanishHooks.silentContainers(entity)) {
			ci.cancel();
		}
	}

	@Inject(method = "stopOpen", at = @At("HEAD"), cancellable = true)
	private void mejtautils$silentClose(ContainerUser user, CallbackInfo ci) {
		if (user instanceof Entity entity && VanishHooks.silentContainers(entity)) {
			ci.cancel();
		}
	}
}
