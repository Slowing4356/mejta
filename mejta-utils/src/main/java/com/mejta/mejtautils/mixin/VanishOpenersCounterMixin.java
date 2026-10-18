package com.mejta.mejtautils.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;

import com.mejta.mejtautils.vanish.VanishHooks;

/** Silent containers: when the game recounts who has a container open, silent vanished staff are not counted. */
@Mixin(ContainerOpenersCounter.class)
public abstract class VanishOpenersCounterMixin {
	@ModifyArg(method = "getEntitiesWithContainerOpen", index = 2, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private Predicate<Entity> mejtautils$skipSilent(Predicate<Entity> predicate) {
		return predicate.and(entity -> !VanishHooks.silentContainers(entity));
	}
}
