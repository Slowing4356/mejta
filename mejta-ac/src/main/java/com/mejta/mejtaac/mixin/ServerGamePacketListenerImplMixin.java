package com.mejta.mejtaac.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.mejta.mejtaac.AntiCheat;

/**
 * Hands serverbound packets to MejtaAC before vanilla validates them. Fabric API has no events for
 * serverbound packets, so mixins are required here.
 *
 * <p>Vanilla handlers first run on the network thread, where
 * {@code PacketUtils.ensureRunningOnSameThread} reschedules them onto the server thread by throwing.
 * Hooks placed right after that call therefore run exactly once, on the server thread, in packet
 * order. The tick-end HEAD hook deliberately runs on the network thread to time packet arrival.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	private static final String ENSURE_SAME_THREAD =
			"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleMovePlayer", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaac$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && antiCheat.onMovePacket(player, packet)) {
			ci.cancel();
		}
	}

	/** Network thread (first pass): arrival time for timer accounting. */
	@Inject(method = "handleClientTickEnd", at = @At("HEAD"))
	private void mejtaac$onClientTickEndArrival(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && !player.level().getServer().packetProcessor().isSameThread()) {
			antiCheat.onClientTickArrival(player.getUUID(), System.nanoTime(), player.level().getServer());
		}
	}

	@Inject(method = "handleClientTickEnd", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaac$onClientTickEnd(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && antiCheat.onClientTickEnd(player)) {
			ci.cancel();
		}
	}

	@Inject(method = "handlePlayerAction", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaac$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
				&& antiCheat.onBlockInteraction(player, packet.getPos(), "start_destroy")) {
			ci.cancel();
		}
	}

	@Inject(method = "handleUseItemOn", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaac$onUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && antiCheat.onBlockInteraction(player, packet.getHitResult().getBlockPos(), "use_on_block")) {
			ci.cancel();
		}
	}

	@Inject(method = "handleAttack", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtaac$onAttack(ServerboundAttackPacket packet, CallbackInfo ci) {
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat != null && antiCheat.onAttack(player, packet.entityId())) {
			ci.cancel();
		}
	}
}
