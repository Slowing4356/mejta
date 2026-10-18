package com.mejta.mejtaac.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.sync.TickSync;

/**
 * Receives pongs for MejtaAC's tick-sync pings. Vanilla handles pongs on the network thread and
 * ignores them, which would let a pong overtake attack packets that were sent before it. MejtaAC's
 * pongs are therefore rescheduled onto the server thread through vanilla's packet queue (the same
 * mechanism every game packet uses), so they are processed in the order the client sent them.
 * Pongs from other mods are untouched.
 *
 * <p>Also watches outgoing packets for velocity changes the server gives the player (knockback,
 * explosions, wind charges, fishing rods), position (teleports, respawns) or abilities (flight,
 * game mode), which the movement checks must allow for, and for corrections of the player's vehicle.
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

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
	private void mejtaac$onSend(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
			return;
		}
		if (changesVelocity(packet, game.player)) {
			AntiCheat.get().ifPresent(antiCheat -> antiCheat.onImpulseSent(game.player));
		}
		if (movesPlayer(packet)) {
			AntiCheat.get().ifPresent(antiCheat -> antiCheat.onTeleportSent(game.player));
		}
		Entity vehicle = game.player.getVehicle();
		if (vehicle != null && correctsVehicle(packet, vehicle.getId())) {
			AntiCheat.get().ifPresent(antiCheat -> antiCheat.onVehicleImpulseSent(game.player));
		}
	}

	/** Packets that move or push the player's vehicle against what the client simulated. */
	private static boolean correctsVehicle(Packet<?> packet, int vehicleId) {
		if (packet instanceof ClientboundMoveVehiclePacket || packet instanceof ClientboundExplodePacket) {
			return true;
		}
		if (packet instanceof ClientboundSetEntityMotionPacket motion) {
			return motion.id() == vehicleId;
		}
		if (packet instanceof ClientboundTeleportEntityPacket teleport) {
			return teleport.id() == vehicleId;
		}
		if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> inner : bundle.subPackets()) {
				if (correctsVehicle(inner, vehicleId)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Teleports (ender pearls, /tp, vanilla's "moved wrongly" corrections) and respawns. */
	private static boolean movesPlayer(Packet<?> packet) {
		if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket) {
			return true;
		}
		if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> inner : bundle.subPackets()) {
				if (movesPlayer(inner)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Server-side changes the client applies only when the packet arrives: velocity, position, and
	 * abilities (flying, flight speed; also sent on every game-mode change).
	 */
	private static boolean changesVelocity(Packet<?> packet, ServerPlayer player) {
		if (packet instanceof ClientboundSetEntityMotionPacket motion) {
			return motion.id() == player.getId();
		}
		if (packet instanceof ClientboundExplodePacket || packet instanceof ClientboundPlayerPositionPacket
				|| packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundPlayerAbilitiesPacket) {
			return true; // explosions push; teleports (ender pearls, /tp) and respawns move; abilities (flight) change physics
		}
		if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> inner : bundle.subPackets()) {
				if (changesVelocity(inner, player)) {
					return true;
				}
			}
		}
		return false;
	}
}
