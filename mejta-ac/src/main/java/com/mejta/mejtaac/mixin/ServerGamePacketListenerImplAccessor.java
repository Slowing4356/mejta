package com.mejta.mejtaac.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/** Lets checks see whether vanilla is waiting for the client to confirm a teleport. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	@Accessor("awaitingPositionFromClient")
	@Nullable Vec3 mejtaac$awaitingPositionFromClient();
}
