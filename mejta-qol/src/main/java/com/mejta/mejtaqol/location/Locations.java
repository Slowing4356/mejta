package com.mejta.mejtaqol.location;

import java.util.Optional;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Converts between {@link Location} and the game, and moves players. */
public final class Locations {
	private Locations() {
	}

	public static Location of(ServerPlayer player) {
		return new Location(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot());
	}

	public static String dimension(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	/** The loaded dimension of {@code location}, or empty if it does not exist (any more). */
	public static Optional<ServerLevel> level(MinecraftServer server, Location location) {
		Identifier id = Identifier.tryParse(location.dimension());
		if (id == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(server.getLevel(ResourceKey.create(Registries.DIMENSION, id)));
	}

	/**
	 * Moves the player: out of a bed or vehicle first, with fall damage reset. Returns false if the
	 * dimension does not exist.
	 */
	public static boolean teleport(ServerPlayer player, Location to) {
		Optional<ServerLevel> level = level(player.level().getServer(), to);
		if (level.isEmpty()) {
			return false;
		}
		if (player.isSleeping()) {
			player.stopSleeping();
		}
		if (player.isPassenger()) {
			player.stopRiding();
		}
		player.teleport(new TeleportTransition(level.get(), new Vec3(to.x(), to.y(), to.z()), Vec3.ZERO, to.yaw(), to.pitch(),
				TeleportTransition.DO_NOTHING));
		player.resetFallDistance();
		return true;
	}
}
