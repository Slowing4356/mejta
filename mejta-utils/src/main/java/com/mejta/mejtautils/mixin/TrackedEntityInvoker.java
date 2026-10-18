package com.mejta.mejtautils.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerPlayer;

/** Re-checks, for each given player, whether they should see this tracked entity (pairs or unpairs). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityInvoker {
	@Invoker("updatePlayers")
	void mejtautils$updatePlayers(List<ServerPlayer> players);
}
