package com.mejta.mejtautils.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak path: player arguments of every command (/msg, /tp, /give...). For a player who cannot see
 * vanished staff, a vanished name gives the same "no player was found" as an offline one.
 */
@Mixin(EntitySelector.class)
public abstract class VanishEntitySelectorMixin {
	@ModifyReturnValue(method = "findPlayers", at = @At("RETURN"))
	private List<ServerPlayer> mejtautils$hideVanished(List<ServerPlayer> original, CommandSourceStack source) {
		return VanishHooks.visibleTo(source.getPlayer(), original);
	}
}
