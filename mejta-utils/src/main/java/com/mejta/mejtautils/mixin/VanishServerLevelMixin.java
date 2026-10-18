package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: the crack animation of a block a vanished player is mining. */
@Mixin(ServerLevel.class)
public abstract class VanishServerLevelMixin {
	@Shadow
	public abstract Entity getEntity(int id);

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void mejtautils$hideMining(ServerGamePacketListenerImpl listener, Packet<?> packet, Operation<Void> original) {
		Entity miner = packet instanceof ClientboundBlockDestructionPacket destruction ? getEntity(destruction.getId()) : null;
		if (!(miner instanceof ServerPlayer player) || !VanishHooks.filtersSounds() || VanishHooks.canSee(listener.player, player)) {
			original.call(listener, packet);
		}
	}
}
