package com.mejta.mejtacore.api.status;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Who is AFK and who is in combat, shared by every addon. Obtain it with {@code MejtaCoreAPI.status()}.
 *
 * <p><b>AFK.</b> MejtaCore notices activity itself: looking around, walking (being carried by a vehicle
 * or water does not count), chatting and interacting. A player idle for {@code afk.auto_minutes}
 * (in {@code mejtacore.json}) becomes AFK; any activity ends it. An addon may also set it, e.g. an
 * {@code /afk} command. Addons that show AFK players (a broadcast, a tag) listen with
 * {@link #addAfkListener}.
 *
 * <p><b>Combat.</b> When a player hurts another player (also with projectiles), both are in combat for
 * {@code combat.tag_seconds}. Death ends the dead player's combat. Addons use it to refuse teleports,
 * AFK protection and the like while a fight is going on.
 *
 * <p>Call these methods on the server thread. Unknown or offline players are neither AFK nor in combat.
 *
 * @since API version 5
 */
public interface PlayerStatusService {
	/** Whether the player is AFK now. */
	boolean isAfk(UUID player);

	/** Milliseconds since the player's last activity; {@code 0} for unknown players. */
	long idleMillis(UUID player);

	/**
	 * Sets or clears AFK on request, e.g. from an {@code /afk} command. Listeners receive
	 * {@link AfkListener.Cause#REQUEST}. Clearing it also counts as activity.
	 *
	 * @return whether the state changed
	 */
	boolean setAfk(ServerPlayer player, boolean afk);

	/** Reports activity MejtaCore cannot see itself (e.g. an addon's own input). Ends AFK. */
	void markActive(ServerPlayer player);

	/** Receives every AFK change from now on, on the server thread. */
	void addAfkListener(AfkListener listener);

	/** Whether the player hurt, or was hurt by, another player within the last {@code combat.tag_seconds}. */
	boolean inCombat(UUID player);

	/** Milliseconds until the player's combat ends; {@code 0} if not in combat. */
	long combatRemainingMillis(UUID player);
}
