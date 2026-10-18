package com.mejta.mejtautils.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: the multiplayer server list (player count and sample names), if vanish.hide_from_server_list is on. */
@Mixin(MinecraftServer.class)
public abstract class VanishServerStatusMixin {
	@Shadow
	public abstract PlayerList getPlayerList();

	@ModifyReceiver(method = "buildPlayerStatus", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
	private List<ServerPlayer> mejtautils$countVisible(List<ServerPlayer> original) {
		return VanishHooks.publiclyVisible(original);
	}

	@ModifyReceiver(method = "buildPlayerStatus", at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"))
	private List<ServerPlayer> mejtautils$sampleVisible(List<ServerPlayer> original, int index) {
		return VanishHooks.publiclyVisible(original);
	}

	@ModifyReturnValue(method = "getPlayerCount", at = @At("RETURN"))
	private int mejtautils$playerCount(int original) {
		PlayerList list = getPlayerList();
		return list == null ? original : VanishHooks.publiclyVisible(list.getPlayers()).size();
	}
}
