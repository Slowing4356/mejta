package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtautils.freeze.FreezeHooks;
import com.mejta.mejtautils.vanish.VanishHooks;

/**
 * Leak paths: picking up items, XP orbs and arrows (unless the staff member allows it), and being hit
 * by projectiles. Frozen players and players in staff mode do not pick anything up either.
 */
@Mixin(Player.class)
public abstract class VanishPlayerMixin {
	@WrapWithCondition(method = "touch", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;playerTouch(Lnet/minecraft/world/entity/player/Player;)V"))
	private boolean mejtautils$preventPickup(Entity entity, Player player) {
		return !VanishHooks.pickupBlocked(player) && !FreezeHooks.pickupBlocked(player);
	}

	@ModifyReturnValue(method = "isPickable", at = @At("RETURN"))
	private boolean mejtautils$passThroughProjectiles(boolean original) {
		return original && !VanishHooks.isVanished((Player) (Object) this);
	}
}
