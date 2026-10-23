package com.mejta.mejtamarket.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.mejta.mejtamarket.menu.SignInput;

/** Catches the answer to a market sign input (see {@link SignInput}) before the game tries to edit a sign that is not there. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class SignUpdateMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
	private void mejtamarket$signInput(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
		if (SignInput.capture(player, packet.getPos(), packet.getLines())) {
			ci.cancel();
		}
	}
}
