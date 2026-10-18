package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak paths: pressure plates, tripwire and big dripleaf react to a vanished player standing in them. */
@Mixin({BasePressurePlateBlock.class, BigDripleafBlock.class, TripWireBlock.class})
public abstract class VanishInsideBlockMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void mejtautils$ignoreVanished(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects,
			boolean intersects, CallbackInfo ci) {
		if (VanishHooks.isVanished(entity)) {
			ci.cancel();
		}
	}
}
