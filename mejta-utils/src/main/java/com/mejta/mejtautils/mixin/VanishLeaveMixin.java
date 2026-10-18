package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;

import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak path: the leave message of a vanished player. The vanish state is still the player's own here:
 * {@code VanishManager.onLeave} (which may drop it when {@code vanish.persist_across_relog} is off) runs
 * later, from Fabric's {@code ServerPlayerEvents.LEAVE} in {@code PlayerList.remove}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VanishLeaveMixin {
	@Shadow
	public ServerPlayer player;

	@WrapOperation(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void mejtautils$hideLeaveMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original) {
		if (VanishHooks.isVanished(player) && MejtaUtils.vanishOrNull() != null) {
			MejtaUtils.vanishOrNull().sendToStaff(list.getServer(), player, message);
		} else {
			original.call(list, message, overlay);
		}
	}
}
