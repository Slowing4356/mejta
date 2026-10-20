package com.mejta.mejtacorpse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** The game's save of one player (data file, stats, advancements), as {@code saveAll} does for each. */
@Mixin(PlayerList.class)
public interface PlayerListInvoker {
	@Invoker("save")
	void mejtacorpse$save(ServerPlayer player);
}
