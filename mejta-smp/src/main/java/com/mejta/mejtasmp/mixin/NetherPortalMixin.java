package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;

import com.mejta.mejtasmp.world.Dimensions;

/**
 * Closed dimensions: a portal into one has no destination, so it takes nobody there (and nothing is built
 * on the other side). A Nether portal leads into the Nether, or back to the Overworld from it.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$closed(ServerLevel currentLevel, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTransition> cir) {
		if (Dimensions.refuse(entity, currentLevel.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER)) {
			cir.setReturnValue(null);
		}
	}
}
