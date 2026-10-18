package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak paths: trampling farmland and breaking turtle eggs by landing on them. */
@Mixin({FarmlandBlock.class, TurtleEggBlock.class})
public abstract class VanishFallOnBlockMixin {
	@Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
	private void mejtautils$ignoreVanished(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
		if (VanishHooks.isVanished(entity)) {
			ci.cancel();
		}
	}
}
