package com.mejta.mejtaqol.spawn;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;

/** {@code /spawn}, {@code /setspawn} and {@code /back}, plus the first-join placement. */
public final class SpawnCommands {
	private static final String KEY = "spawn";

	private SpawnCommands() {
	}

	public static void register() {
		String id = MejtaQoL.ID;
		MejtaCoreAPI.registerRootCommand(id, "spawn", (root, ctx) -> root
				.requires(Cmds.available(Features.SPAWN))
				.executes(c -> spawn(c.getSource().getPlayerOrException())));
		MejtaCoreAPI.registerRootCommand(id, "setspawn", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.SPAWN_ENABLED, QolPermissions.SETSPAWN))
				.executes(c -> setSpawn(c.getSource().getPlayerOrException())));
		MejtaCoreAPI.registerRootCommand(id, "back", (root, ctx) -> root
				.requires(Cmds.available(Features.BACK))
				.executes(c -> back(c.getSource().getPlayerOrException())));
	}

	/** The point set with {@code /setspawn}, if any. */
	public static Optional<Location> customSpawn(DataStore store) {
		return store.read(KEY).flatMap(Location::fromJson);
	}

	/** Where {@code /spawn} goes: the {@code /setspawn} point, else the world spawn. */
	public static Location spawnLocation(MinecraftServer server, ServerPlayer player) {
		Optional<Location> custom = customSpawn(MejtaQoL.store());
		if (custom.isPresent()) {
			return custom.get();
		}
		var data = server.overworld().getRespawnData();
		ServerLevel level = Optional.ofNullable(server.getLevel(data.globalPos().dimension())).orElse(server.overworld());
		BlockPos safe = player.adjustSpawnLocation(level, data.globalPos().pos());
		Vec3 pos = safe.getBottomCenter();
		return new Location(Locations.dimension(level), pos.x, pos.y, pos.z, data.yaw(), data.pitch());
	}

	public static int spawn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return MejtaQoL.teleports().request(player, Features.SPAWN, Component.literal("spawn"),
				t -> Teleports.Result.to(spawnLocation(server, t))) ? 1 : 0;
	}

	private static int setSpawn(ServerPlayer player) {
		Location here = Locations.of(player);
		MejtaQoL.store().write(KEY, here.toJson());
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaQoL.ID, "spawn.set")
				.actor(MejtaCoreAPI.actor(player))
				.outcome(AuditOutcome.SUCCESS)
				.detail("location", here.describe())
				.build());
		player.sendSystemMessage(Msg.success("The /spawn point is now " + here.describe() + "."));
		return 1;
	}

	public static int back(ServerPlayer player) {
		Optional<Location> to = MejtaQoL.teleports().backLocation(player.getUUID());
		if (to.isEmpty()) {
			player.sendSystemMessage(Msg.error("There is nowhere to go back to yet."));
			return 0;
		}
		Location target = to.get();
		return MejtaQoL.teleports().request(player, Features.BACK, Component.literal("your previous location"),
				t -> Teleports.Result.to(target)) ? 1 : 0;
	}

	/** On join: puts a first-time player at the {@code /setspawn} point if spawn.on_first_join is on. */
	public static void onFirstJoin(ServerPlayer player) {
		if (!MejtaQoL.config().get(QolSettings.SPAWN_ENABLED) || !MejtaQoL.config().get(QolSettings.SPAWN_ON_FIRST_JOIN)) {
			return;
		}
		customSpawn(MejtaQoL.store()).ifPresent(spawn -> Locations.teleport(player, spawn));
	}
}
