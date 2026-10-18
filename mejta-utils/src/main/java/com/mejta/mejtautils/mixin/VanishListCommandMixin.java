package com.mejta.mejtautils.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: /list. */
@Mixin(ListPlayersCommand.class)
public abstract class VanishListCommandMixin {
	@Redirect(method = "format", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
	private static List<ServerPlayer> mejtautils$visiblePlayers(PlayerList list, CommandSourceStack source) {
		return VanishHooks.visibleTo(source.getPlayer(), list.getPlayers());
	}
}
