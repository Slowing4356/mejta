package com.mejta.mejtaac.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.sync.TickSync;

/**
 * Receives pongs for MejtaAC's tick-sync pings. Vanilla handles pongs on the network thread and
 * ignores them, which would let a pong overtake attack packets that were sent before it. MejtaAC's
 * pongs are therefore rescheduled onto the server thread through vanilla's packet queue (the same
 * mechanism every game packet uses), so they are processed in the order the client sent them.
 * Pongs from other mods are untouched.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
	@Inject(method = "handlePong", at = @At("HEAD"))
	private void mejtaac$onPong(ServerboundPongPacket packet, CallbackInfo ci) {
		if (!TickSync.isOurs(packet.getId()) || !((Object) this instanceof ServerGamePacketListenerImpl game)) {
			return;
		}
		AntiCheat antiCheat = AntiCheat.get().orElse(null);
		if (antiCheat == null) {
			return;
		}
		ServerPlayer player = game.player;
		if (!player.level().getServer().packetProcessor().isSameThread()) {
			antiCheat.onPongArrival(player.getUUID(), System.nanoTime());
			PacketUtils.ensureRunningOnSameThread(packet, game, player.level()); // throws: rescheduled in order
			return;
		}
		antiCheat.onPong(player, packet.getId());
	}
}
