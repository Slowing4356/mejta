package com.mejta.mejtautils.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * The single choke point for tab-list data: every player-info packet (join, latency and game-mode
 * updates the server sends regularly, display names) is filtered per recipient, so vanished players
 * never appear in the tab list of players who cannot see them. Also hides the pickup animation of a
 * vanished player picking up an item.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class VanishPacketMixin {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Shadow
	public abstract void send(Packet<?> packet);

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void mejtautils$filter(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
			return;
		}
		ServerPlayer recipient = game.player;
		if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
			List<ClientboundPlayerInfoUpdatePacket.Entry> kept = new ArrayList<>(info.entries().size());
			for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
				ServerPlayer subject = server.getPlayerList().getPlayer(entry.profileId());
				if (subject == null || VanishHooks.canSee(recipient, subject)) {
					kept.add(entry);
				}
			}
			if (kept.size() == info.entries().size()) {
				return; // nothing hidden: send as is (this also ends the recursion below)
			}
			ci.cancel();
			if (!kept.isEmpty()) {
				ClientboundPlayerInfoUpdatePacket filtered = new ClientboundPlayerInfoUpdatePacket(info.actions(), List.of());
				((ClientboundPlayerInfoUpdatePacketAccessor) filtered).mejtautils$setEntries(kept);
				send(filtered);
			}
		} else if (packet instanceof ClientboundTakeItemEntityPacket take) {
			Entity actor = recipient.level().getEntity(take.getPlayerId());
			if (actor instanceof ServerPlayer picker && !VanishHooks.canSee(recipient, picker)) {
				ci.cancel();
				send(new ClientboundRemoveEntitiesPacket(take.getItemId()));
			}
		}
	}
}
