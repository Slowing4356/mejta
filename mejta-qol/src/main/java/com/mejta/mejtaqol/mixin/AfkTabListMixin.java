package com.mejta.mejtaqol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.afk.AfkManager;

/** Adds {@code [AFK]} after an AFK player's name in the player list (afk.tab_list). */
@Mixin(ServerPlayer.class)
public abstract class AfkTabListMixin {
	@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
	private void mejtaqol$afkTag(CallbackInfoReturnable<Component> cir) {
		AfkManager afk = MejtaQoL.afkOrNull();
		if (afk == null) {
			return;
		}
		Component tagged = afk.tabListName((ServerPlayer) (Object) this, cir.getReturnValue());
		if (tagged != null) {
			cir.setReturnValue(tagged);
		}
	}
}
