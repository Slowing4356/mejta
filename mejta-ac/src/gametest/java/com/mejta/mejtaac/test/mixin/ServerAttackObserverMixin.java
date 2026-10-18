package com.mejta.mejtaac.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

import com.mejta.mejtaac.test.TestHooks;

/**
 * Test-only observer on the in-process server: groups the attack packets a real client sends by
 * client tick, to verify the assumption behind {@code combat.multi_target}, and counts block actions.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerAttackObserverMixin {
	private static final String ENSURE_SAME_THREAD =
			"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleAttack", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaacTest$observeAttack(ServerboundAttackPacket packet, CallbackInfo ci) {
		Entity target = player.level().getEntity(packet.entityId());
		double distance = target == null ? 0 : Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
		TestHooks.onServerAttack(packet.entityId(), distance);
	}

	@Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaacTest$observeAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
			TestHooks.blockActionsReceived++;
		}
	}

	@Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaacTest$observeUseOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		TestHooks.blockActionsReceived++;
	}

	@Inject(method = "handleClientTickEnd", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaacTest$observeTickEnd(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
		TestHooks.onServerClientTickEnd();
	}
}
