package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.ExperienceOrb;

/** The orb fields clumping changes. */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccessor {
	@Accessor("count")
	int mejtasmp$count();

	@Accessor("count")
	void mejtasmp$setCount(int count);

	@Accessor("age")
	int mejtasmp$age();

	@Accessor("age")
	void mejtasmp$setAge(int age);

	@Invoker("setValue")
	void mejtasmp$setValue(int value);
}
