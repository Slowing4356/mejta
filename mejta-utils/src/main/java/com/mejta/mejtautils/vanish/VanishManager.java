package com.mejta.mejtautils.vanish;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.presence.PresenceListener;
import com.mejta.mejtacore.api.presence.VisibilityProvider;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.mixin.ChunkMapAccessor;
import com.mejta.mejtautils.mixin.TrackedEntityInvoker;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * Vanish: who is hidden, who may see them, and switching it on and off. The mixins in
 * {@code com.mejta.mejtautils.mixin} ask this class at each leak path (entity tracking, tab list,
 * server list, sounds, game events, mobs, blocks...). It is also MejtaCore's visibility provider, so
 * every Mejta addon that shows players respects vanish.
 *
 * <p>All methods run on the server thread; the state map is concurrent only because packet sending
 * can happen on network threads.
 */
public final class VanishManager implements VisibilityProvider {
	private static final String FOLDER = "vanish";
	private static final float VANILLA_FLY_SPEED = 0.05f;

	private final DataStore store;
	private final ConfigHandle config;
	private final Map<UUID, VanishState> states = new ConcurrentHashMap<>();

	public VanishManager(DataStore store, ConfigHandle config) {
		this.store = store;
		this.config = config;
	}

	public VanishState state(UUID player) {
		return states.computeIfAbsent(player, id -> store.read(DataStore.playerKey(FOLDER, id))
				.map(json -> VanishState.fromJson(json, config))
				.orElseGet(() -> VanishState.defaults(config)));
	}

	public void save(UUID player) {
		store.write(DataStore.playerKey(FOLDER, player), state(player).toJson());
	}

	// ---------------------------------------------------------------- visibility (asked by mixins)

	@Override
	public boolean isHidden(UUID player) {
		VanishState s = states.get(player);
		if (s == null) {
			s = state(player);
		}
		return s.vanished;
	}

	public boolean isVanished(Entity entity) {
		return entity instanceof ServerPlayer player && isHidden(player.getUUID());
	}

	@Override
	public boolean canSeeHidden(ServerPlayer viewer, UUID target) {
		return MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.VANISH_SEE);
	}

	/** Whether {@code viewer} may see {@code target}. */
	public boolean canSee(Player viewer, ServerPlayer target) {
		return viewer == target || !isHidden(target.getUUID())
				|| (viewer instanceof ServerPlayer sp && canSeeHidden(sp, target.getUUID()));
	}

	/** The vanished player behind an entity: the player itself, or the owner of their projectile. */
	public ServerPlayer actorOf(Entity entity) {
		if (entity instanceof ServerPlayer player) {
			return player;
		}
		if (entity instanceof TraceableEntity traceable && traceable.getOwner() instanceof ServerPlayer owner) {
			return owner;
		}
		return null;
	}

	/** Whether {@code viewer} may see what {@code entity} (a player or their projectile) does. */
	public boolean canSeeActor(Player viewer, Entity entity) {
		ServerPlayer actor = actorOf(entity);
		return actor == null || canSee(viewer, actor);
	}

	// ---------------------------------------------------------------- switching

	/** Turns vanish on or off; permission is checked by the caller. */
	public void setVanished(ServerPlayer player, boolean vanish, AuditActor actor) {
		VanishState s = state(player.getUUID());
		if (s.vanished == vanish) {
			return;
		}
		s.vanished = vanish;
		if (!vanish) {
			s.fakeOffline = false;
		}
		save(player.getUUID());
		if (vanish) {
			s.hadFlight = player.getAbilities().mayfly;
			applyEffects(player, s);
			clearMobTargets(player);
		} else {
			removeEffects(player, s);
		}
		refreshVisibility(player, vanish);
		player.level().getServer().invalidateStatus();
		player.sendSystemMessage(Component.literal(vanish ? "You are vanished." : "You are visible again.")
				.withStyle(vanish ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
		UtilsAudit.record(vanish ? "vanish.on" : "vanish.off", actor, player.getUUID(), player.getGameProfile().name(),
				AuditOutcome.SUCCESS, Map.of(), UtilsPermissions.VANISH_SEE);
	}

	/**
	 * Sends tab-list changes to everyone who cannot see vanished players, then makes the game's entity
	 * tracker re-check who sees {@code player} (it otherwise only re-checks after the player moves to
	 * another chunk section).
	 */
	void refreshVisibility(ServerPlayer player, boolean hidden) {
		MinecraftServer server = player.level().getServer();
		Packet<?> info = hidden
				? new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()))
				: ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other != player && !canSeeHidden(other, player.getUUID())) {
				other.connection.send(info);
			}
		}
		ServerLevel level = player.level();
		Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).mejtautils$entityMap().get(player.getId());
		if (tracked != null) {
			((TrackedEntityInvoker) tracked).mejtautils$updatePlayers(level.players());
		}
	}

	private void applyEffects(ServerPlayer player, VanishState s) {
		if (s.nightVision) {
			player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		}
		GameType mode = player.gameMode.getGameModeForPlayer();
		if (mode == GameType.SURVIVAL || mode == GameType.ADVENTURE) {
			player.getAbilities().mayfly = true;
		}
		player.getAbilities().setFlyingSpeed((float) (VANILLA_FLY_SPEED * s.flightSpeed));
		player.onUpdateAbilities();
		save(player.getUUID());
	}

	private void removeEffects(ServerPlayer player, VanishState s) {
		MobEffectInstance nv = player.getEffect(MobEffects.NIGHT_VISION);
		if (nv != null && nv.isInfiniteDuration() && !nv.isVisible()) {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
		GameType mode = player.gameMode.getGameModeForPlayer();
		if ((mode == GameType.SURVIVAL || mode == GameType.ADVENTURE) && !s.hadFlight) {
			player.getAbilities().mayfly = false;
			player.getAbilities().flying = false;
		}
		player.getAbilities().setFlyingSpeed(VANILLA_FLY_SPEED);
		player.onUpdateAbilities();
	}

	/** Re-applies settings that changed while vanished (from /vanish settings). */
	public void settingsChanged(ServerPlayer player) {
		VanishState s = state(player.getUUID());
		save(player.getUUID());
		if (!s.vanished) {
			return;
		}
		if (!s.nightVision) {
			MobEffectInstance nv = player.getEffect(MobEffects.NIGHT_VISION);
			if (nv != null && nv.isInfiniteDuration() && !nv.isVisible()) {
				player.removeEffect(MobEffects.NIGHT_VISION);
			}
		}
		applyEffects(player, s);
		if (!s.mobTargeting) {
			clearMobTargets(player);
		}
	}

	private static void clearMobTargets(ServerPlayer player) {
		for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(64))) {
			if (mob.getTarget() == player) {
				mob.setTarget(null);
			}
		}
	}

	// ---------------------------------------------------------------- join, leave, fake presence

	/** On join (server thread, after the player is placed): keep or drop the saved vanish state. */
	public void onJoin(ServerPlayer player) {
		VanishState s = state(player.getUUID());
		if (!s.vanished) {
			return;
		}
		if (!MejtaCoreAPI.hasPermission(player, UtilsPermissions.VANISH)) {
			setVanished(player, false, AuditActor.system(MejtaUtils.ID));
			return;
		}
		applyEffects(player, s);
		player.sendSystemMessage(Component.literal("You joined vanished.").withStyle(ChatFormatting.GREEN));
	}

	/** On leave: without persistence, the next join is visible again. */
	public void onLeave(ServerPlayer player) {
		if (!config.get(UtilsSettings.VANISH_PERSIST)) {
			VanishState s = state(player.getUUID());
			if (s.vanished) {
				s.vanished = false;
				s.fakeOffline = false;
				save(player.getUUID());
			}
		}
		states.remove(player.getUUID());
	}

	/** Pretends {@code player} left: vanishes them, shows the vanilla leave message and tells MejtaCore's presence listeners. */
	public void fakeQuit(ServerPlayer player, AuditActor actor) {
		setVanished(player, true, actor);
		VanishState s = state(player.getUUID());
		s.fakeOffline = true;
		save(player.getUUID());
		broadcastToNonStaff(player, Component.translatable("multiplayer.player.left", player.getDisplayName()).withStyle(ChatFormatting.YELLOW));
		MejtaCoreAPI.presence().announce(player.getUUID(), player.getGameProfile().name(), PresenceListener.Kind.LEAVE);
		UtilsAudit.record("vanish.fakequit", actor, player.getUUID(), player.getGameProfile().name(), AuditOutcome.SUCCESS, Map.of(),
				UtilsPermissions.VANISH_SEE);
	}

	/** Pretends {@code player} joined: unvanishes them, shows the vanilla join message and tells presence listeners. */
	public void fakeJoin(ServerPlayer player, AuditActor actor) {
		setVanished(player, false, actor);
		broadcastToNonStaff(player, Component.translatable("multiplayer.player.joined", player.getDisplayName()).withStyle(ChatFormatting.YELLOW));
		MejtaCoreAPI.presence().announce(player.getUUID(), player.getGameProfile().name(), PresenceListener.Kind.JOIN);
		UtilsAudit.record("vanish.fakejoin", actor, player.getUUID(), player.getGameProfile().name(), AuditOutcome.SUCCESS, Map.of(),
				UtilsPermissions.VANISH_SEE);
	}

	private void broadcastToNonStaff(ServerPlayer about, Component message) {
		for (ServerPlayer other : about.level().getServer().getPlayerList().getPlayers()) {
			if (other != about && !canSeeHidden(other, about.getUUID())) {
				other.sendSystemMessage(message);
			}
		}
		about.sendSystemMessage(Component.literal("(shown to players who cannot see vanished staff) ").withStyle(ChatFormatting.GRAY)
				.append(message));
	}

	/** Shows a message (e.g. a suppressed join message) only to staff who can see vanished players. */
	public void sendToStaff(MinecraftServer server, ServerPlayer about, Component message) {
		Component tagged = Component.literal("[vanished] ").withStyle(ChatFormatting.GRAY).append(message);
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other != about && canSeeHidden(other, about.getUUID())) {
				other.sendSystemMessage(tagged);
			}
		}
	}
}
