package com.mejta.mejtautils.mixin;

import java.util.ArrayList;
import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Leak path: player names the server suggests while typing a command. */
@Mixin(CommandSourceStack.class)
public abstract class VanishSuggestionsMixin {
	@ModifyReturnValue(method = "getOnlinePlayerNames", at = @At("RETURN"))
	private Collection<String> mejtautils$hideVanishedNames(Collection<String> original) {
		CommandSourceStack self = (CommandSourceStack) (Object) this;
		ServerPlayer viewer = self.getPlayer();
		if (viewer == null) {
			return original;
		}
		Collection<String> out = new ArrayList<>(original.size());
		for (ServerPlayer p : VanishHooks.visibleTo(viewer, self.getServer().getPlayerList().getPlayers())) {
			out.add(p.getGameProfile().name());
		}
		return out;
	}
}
