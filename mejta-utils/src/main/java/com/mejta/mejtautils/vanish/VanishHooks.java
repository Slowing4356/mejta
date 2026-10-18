package com.mejta.mejtautils.vanish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsSettings;

/**
 * Static entry points for the vanish mixins. Every method is safe to call before MejtaUtils is
 * initialized or if it failed: then nobody is vanished and vanilla behaviour is unchanged.
 */
public final class VanishHooks {
	private VanishHooks() {
	}

	private static VanishManager manager() {
		return MejtaUtils.vanishOrNull();
	}

	public static boolean isVanished(Entity entity) {
		VanishManager m = manager();
		return m != null && m.isVanished(entity);
	}

	/** The entity is a vanished player or a vanished player's projectile. */
	public static boolean isVanishedActor(Entity entity) {
		VanishManager m = manager();
		if (m == null) {
			return false;
		}
		ServerPlayer actor = m.actorOf(entity);
		return actor != null && m.isHidden(actor.getUUID());
	}

	public static boolean canSee(Player viewer, ServerPlayer target) {
		VanishManager m = manager();
		return m == null || m.canSee(viewer, target);
	}

	public static boolean canSeeActor(Player viewer, Entity entity) {
		VanishManager m = manager();
		return m == null || m.canSeeActor(viewer, entity);
	}

	/** Players {@code viewer} may see; a console or command block (null) sees everyone. */
	public static List<ServerPlayer> visibleTo(ServerPlayer viewer, Collection<ServerPlayer> players) {
		VanishManager m = manager();
		if (m == null || viewer == null) {
			return players instanceof List<ServerPlayer> list ? list : new ArrayList<>(players);
		}
		List<ServerPlayer> out = new ArrayList<>(players.size());
		for (ServerPlayer p : players) {
			if (m.canSee(viewer, p)) {
				out.add(p);
			}
		}
		return out;
	}

	/** Players regular players see: for the server list and its player count. */
	public static List<ServerPlayer> publiclyVisible(Collection<ServerPlayer> players) {
		VanishManager m = manager();
		if (m == null || !MejtaUtils.config().get(UtilsSettings.VANISH_HIDE_SERVER_LIST)) {
			return players instanceof List<ServerPlayer> list ? list : new ArrayList<>(players);
		}
		List<ServerPlayer> out = new ArrayList<>(players.size());
		for (ServerPlayer p : players) {
			if (!m.isHidden(p.getUUID())) {
				out.add(p);
			}
		}
		return out;
	}

	public static boolean pickupBlocked(Player player) {
		VanishManager m = manager();
		return m != null && player instanceof ServerPlayer sp && m.isHidden(sp.getUUID()) && !m.state(sp.getUUID()).itemPickup;
	}

	public static boolean hiddenFromMobs(Entity entity) {
		VanishManager m = manager();
		return m != null && entity instanceof ServerPlayer sp && m.isHidden(sp.getUUID()) && !m.state(sp.getUUID()).mobTargeting;
	}

	public static boolean silentContainers(Entity entity) {
		VanishManager m = manager();
		return m != null && entity instanceof ServerPlayer sp && m.isHidden(sp.getUUID()) && m.state(sp.getUUID()).silentContainers;
	}

	public static boolean blocksGameEvents(Entity source) {
		VanishManager m = manager();
		return m != null && MejtaUtils.config().get(UtilsSettings.VANISH_BLOCK_GAME_EVENTS) && isVanishedActor(source);
	}

	public static boolean filtersSounds() {
		VanishManager m = manager();
		return m != null && MejtaUtils.config().get(UtilsSettings.VANISH_FILTER_SOUNDS);
	}
}
