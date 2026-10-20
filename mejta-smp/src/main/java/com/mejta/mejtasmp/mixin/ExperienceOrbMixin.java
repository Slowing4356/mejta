package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtasmp.xp.XpClumps;

/** {@link XpClumps}: orbs merge whatever their value, and a player takes a touching orb at once. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	/** Set while the instant pickup runs vanilla's pickup for each part of the orb. */
	@Unique
	private static boolean mejtasmp$picking;

	@Inject(method = "scanForMerges", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$clump(CallbackInfo ci) {
		if (XpClumps.clump((ExperienceOrb) (Object) this)) {
			ci.cancel();
		}
	}

	/**
	 * Vanilla takes one part of an orb every 2 ticks. This runs vanilla's own pickup (Mending included)
	 * for every part now, and leaves the player ready for the next orb in the same tick.
	 */
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$instant(Player player, CallbackInfo ci) {
		if (mejtasmp$picking || !(player instanceof ServerPlayer) || !XpClumps.instant()) {
			return;
		}
		ExperienceOrb self = (ExperienceOrb) (Object) this;
		mejtasmp$picking = true;
		try {
			for (int i = 0; i < XpClumps.MAX_PARTS && !self.isRemoved(); i++) {
				player.takeXpDelay = 0;
				self.playerTouch(player);
			}
			player.takeXpDelay = 0;
		} finally {
			mejtasmp$picking = false;
		}
		ci.cancel();
	}
}
