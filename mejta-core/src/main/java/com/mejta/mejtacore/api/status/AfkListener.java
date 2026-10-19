package com.mejta.mejtacore.api.status;

import net.minecraft.server.level.ServerPlayer;

/**
 * Receives AFK changes from {@link PlayerStatusService}. Called on the server thread.
 *
 * @since API version 5
 */
@FunctionalInterface
public interface AfkListener {
	enum Cause {
		/** Idle for {@code afk.auto_minutes}. */
		IDLE,
		/** The player moved, chatted or interacted. */
		ACTIVITY,
		/** An addon set or cleared it, e.g. an {@code /afk} command. */
		REQUEST
	}

	void onAfkChange(ServerPlayer player, boolean afk, Cause cause);
}
