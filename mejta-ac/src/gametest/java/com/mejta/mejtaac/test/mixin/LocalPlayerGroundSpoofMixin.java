package com.mejta.mejtaac.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.player.LocalPlayer;

import com.mejta.mejtaac.test.TestHooks;

/** Test-only cheat simulation: makes the real client claim onGround=true in its movement packets. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerGroundSpoofMixin {
	@Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
	private boolean mejtaacTest$spoofGround(LocalPlayer self) {
		return TestHooks.spoofGround || self.onGround();
	}
}
