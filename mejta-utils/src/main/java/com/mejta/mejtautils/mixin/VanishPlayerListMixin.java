package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak paths: the join message of a player who joins vanished, and everything the server broadcasts
 * "to everyone near X except X" (sounds, block-break particles, level events) where X is vanished.
 */
@Mixin(PlayerList.class)
public abstract class VanishPlayerListMixin {
	@WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void mejtautils$hideJoinMessage(PlayerList list, Component message, boolean overlay, Operation<Void> original,
			Connection connection, ServerPlayer joining) {
		if (VanishHooks.isVanished(joining) && MejtaUtils.vanishOrNull() != null) {
			MejtaUtils.vanishOrNull().sendToStaff(list.getServer(), joining, message);
		} else {
			original.call(list, message, overlay);
		}
	}

	@WrapWithCondition(method = "broadcast", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private boolean mejtautils$hideNearbyEffects(ServerGamePacketListenerImpl listener, Packet<?> packet, Player except) {
		return except == null || !VanishHooks.filtersSounds() || VanishHooks.canSeeActor(listener.player, except);
	}
}
