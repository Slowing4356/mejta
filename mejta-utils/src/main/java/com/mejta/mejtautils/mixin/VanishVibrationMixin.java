package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak path: vibrations (sculk sensors, shriekers, Warden listening, allays). Game events caused by a
 * vanished player or their projectiles are not heard (vanish.block_game_events, experimental).
 */
@Mixin(VibrationSystem.Listener.class)
public abstract class VanishVibrationMixin {
	@Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
	private void mejtautils$ignoreVanished(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos,
			CallbackInfoReturnable<Boolean> cir) {
		Entity source = context.sourceEntity();
		if (source != null && VanishHooks.blocksGameEvents(source)) {
			cir.setReturnValue(false);
		}
	}
}
