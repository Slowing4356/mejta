package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.server.level.ChunkMap;

/** The tracked entities by entity ID, to make the tracker re-check one player when vanish changes. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("entityMap")
	Int2ObjectMap<?> mejtautils$entityMap();
}
