package com.mejta.mejtacorpse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtacorpse.CorpseManager;
import com.mejta.mejtacorpse.MejtaCorpse;

/**
 * Where a dying player's inventory would be dropped (only when keepInventory is off, and after items with
 * Curse of Vanishing are destroyed), the items go into a corpse instead. Whatever the corpse did not take
 * is then dropped by the game as usual.
 */
@Mixin(Player.class)
public abstract class DeathDropMixin {
	@WrapOperation(method = "dropEquipment", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;dropAll()V"))
	private void mejtacorpse$intoCorpse(Inventory inventory, Operation<Void> original) {
		CorpseManager manager = MejtaCorpse.managerOrNull();
		if (manager != null && (Object) this instanceof ServerPlayer player) {
			try {
				manager.capture(player, CorpseManager.SAVE_ALL);
			} catch (RuntimeException e) {
				MejtaCorpse.log().error("Corpse creation failed for {}; their items drop instead", player.getGameProfile().name(), e);
			}
		}
		original.call(inventory);
	}
}
