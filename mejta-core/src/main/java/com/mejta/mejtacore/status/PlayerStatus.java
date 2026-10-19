package com.mejta.mejtacore.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.status.AfkListener;
import com.mejta.mejtacore.api.status.PlayerStatusService;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.logging.Diagnostics;

/** {@link PlayerStatusService} implementation. Server thread only, apart from the thread-safe reads. */
public final class PlayerStatus implements PlayerStatusService {
	private record Pose(double x, double y, double z, float yaw, float pitch) {
	}

	private static final PlayerStatus INSTANCE = new PlayerStatus();

	private final StatusClock clock = new StatusClock();
	private final Map<UUID, Pose> poses = new ConcurrentHashMap<>();
	private final List<AfkListener> listeners = new CopyOnWriteArrayList<>();
	private volatile ConfigHandle config;

	private PlayerStatus() {
	}

	public static PlayerStatus get() {
		return INSTANCE;
	}

	/** Common init: activity, idle checks and combat come from the game's events. */
	public static void register(ConfigHandle coreConfig) {
		INSTANCE.config = coreConfig;
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> INSTANCE.join(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> INSTANCE.leave(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> INSTANCE.reset());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 0) {
				INSTANCE.everySecond(server);
			}
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, type) -> INSTANCE.markActive(sender));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> active(player));
		UseItemCallback.EVENT.register((player, level, hand) -> active(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> active(player));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> active(player));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> active(player));
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (damage > 0 && entity instanceof ServerPlayer victim && source.getEntity() instanceof ServerPlayer attacker
					&& attacker != victim) {
				INSTANCE.clock.combat(victim.getUUID(), attacker.getUUID(), System.currentTimeMillis());
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				INSTANCE.clock.endCombat(player.getUUID());
			}
		});
	}

	private static InteractionResult active(Player player) {
		if (player instanceof ServerPlayer sp) {
			INSTANCE.markActive(sp);
		}
		return InteractionResult.PASS;
	}

	// ---------------------------------------------------------------- API

	@Override
	public boolean isAfk(UUID player) {
		return clock.isAfk(player);
	}

	@Override
	public long idleMillis(UUID player) {
		return clock.idleMillis(player, System.currentTimeMillis());
	}

	@Override
	public boolean setAfk(ServerPlayer player, boolean afk) {
		StatusClock.Change change = clock.set(player.getUUID(), afk, System.currentTimeMillis());
		poses.put(player.getUUID(), pose(player));
		fire(player, change, AfkListener.Cause.REQUEST);
		return change != StatusClock.Change.NONE;
	}

	@Override
	public void markActive(ServerPlayer player) {
		fire(player, clock.active(player.getUUID(), System.currentTimeMillis()), AfkListener.Cause.ACTIVITY);
	}

	@Override
	public void addAfkListener(AfkListener listener) {
		listeners.add(listener);
	}

	@Override
	public boolean inCombat(UUID player) {
		return combatRemainingMillis(player) > 0;
	}

	@Override
	public long combatRemainingMillis(UUID player) {
		ConfigHandle c = config;
		return c == null ? 0 : clock.combatRemaining(player, System.currentTimeMillis(), c.get(CoreSettings.COMBAT_TAG_SECONDS));
	}

	// ---------------------------------------------------------------- events

	void join(ServerPlayer player) {
		clock.join(player.getUUID(), System.currentTimeMillis());
		poses.put(player.getUUID(), pose(player));
	}

	void leave(UUID player) {
		clock.leave(player);
		poses.remove(player);
	}

	/** Compares each player's pose with the last one, then applies the idle rule. */
	void everySecond(MinecraftServer server) {
		long now = System.currentTimeMillis();
		int auto = config == null ? 0 : config.get(CoreSettings.AFK_AUTO_MINUTES);
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			Pose current = pose(player);
			Pose before = poses.put(player.getUUID(), current);
			if (before != null && moved(player, before, current)) {
				markActive(player);
			} else {
				fire(player, clock.check(player.getUUID(), now, auto), AfkListener.Cause.IDLE);
			}
		}
	}

	private void fire(ServerPlayer player, StatusClock.Change change, AfkListener.Cause cause) {
		if (change == StatusClock.Change.NONE) {
			return;
		}
		boolean afk = change == StatusClock.Change.BECAME_AFK;
		for (AfkListener listener : listeners) {
			try {
				listener.onAfkChange(player, afk, cause);
			} catch (RuntimeException e) {
				Diagnostics.error("mejtacore", "An AFK listener failed", e);
			}
		}
	}

	private static Pose pose(ServerPlayer p) {
		return new Pose(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	private static boolean moved(ServerPlayer player, Pose a, Pose b) {
		if (Math.abs(a.yaw() - b.yaw()) > 1 || Math.abs(a.pitch() - b.pitch()) > 1) {
			return true;
		}
		if (player.isPassenger() || player.isInWater()) {
			return false;
		}
		double dx = a.x() - b.x();
		double dz = a.z() - b.z();
		return dx * dx + dz * dz > 0.01;
	}

	void reset() {
		clock.clear();
		poses.clear();
	}
}
