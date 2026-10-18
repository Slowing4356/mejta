package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.waypoints.WaypointTransmitter;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: the locator bar, which shows other players' direction. */
@Mixin(WaypointTransmitter.class)
public interface VanishWaypointMixin {
	@Inject(method = "doesSourceIgnoreReceiver", at = @At("HEAD"), cancellable = true)
	private static void mejtautils$hideVanished(LivingEntity source, ServerPlayer receiver, CallbackInfoReturnable<Boolean> cir) {
		if (source instanceof ServerPlayer actor && !VanishHooks.canSee(receiver, actor)) {
			cir.setReturnValue(true);
		}
	}
}
