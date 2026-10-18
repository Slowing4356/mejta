package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak paths: stepping on redstone ore (lights up), sculk sensors and shriekers, turtle eggs. */
@Mixin({RedStoneOreBlock.class, SculkSensorBlock.class, SculkShriekerBlock.class, TurtleEggBlock.class})
public abstract class VanishStepOnBlockMixin {
	@Inject(method = "stepOn", at = @At("HEAD"), cancellable = true)
	private void mejtautils$ignoreVanished(Level level, BlockPos pos, BlockState state, Entity entity, CallbackInfo ci) {
		if (VanishHooks.isVanished(entity)) {
			ci.cancel();
		}
	}
}
