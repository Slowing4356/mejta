package com.mejta.mejtasmp.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.item.ItemEntity;

/** The item entity fields piles copy to the entity that continues them. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
	@Accessor("age")
	int mejtasmp$age();

	@Accessor("age")
	void mejtasmp$setAge(int age);

	@Accessor("pickupDelay")
	int mejtasmp$pickupDelay();

	@Accessor("pickupDelay")
	void mejtasmp$setPickupDelay(int delay);

	@Accessor("target")
	UUID mejtasmp$target();
}
