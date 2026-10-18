package com.mejta.mejtacore.api.presence;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/**
 * Supplies hidden ("vanished") players to {@link PresenceService}. A vanish addon registers one;
 * other addons only ask the presence service and never depend on the vanish addon.
 *
 * <p>Methods are called on the server thread and must be fast (a map lookup).
 *
 * @since API version 4
 */
public interface VisibilityProvider {
	/** Whether {@code player} is hidden from regular players. Must also work for offline players' UUIDs. */
	boolean isHidden(UUID player);

	/** Whether {@code viewer} may see the hidden {@code target} anyway (staff). Only asked if the target is hidden. */
	default boolean canSeeHidden(ServerPlayer viewer, UUID target) {
		return false;
	}
}
