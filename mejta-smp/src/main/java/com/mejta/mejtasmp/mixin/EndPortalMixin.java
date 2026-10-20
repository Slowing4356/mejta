package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;

import com.mejta.mejtasmp.world.Dimensions;

/**
 * Closed dimensions: a portal into one has no destination, so it takes nobody there (and nothing is built
 * on the other side). An End portal outside the End leads into the End; the way back is always open.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$closed(ServerLevel currentLevel, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
		if (currentLevel.dimension() != Level.END && Dimensions.refuse(entity, Level.END)) {
			cir.setReturnValue(null);
		}
	}
}
