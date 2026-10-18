package com.mejta.mejtacore.api.presence;

import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is publicly online. Obtain it with {@code MejtaCoreAPI.presence()}.
 *
 * <p>Use it wherever an addon shows or announces players: chat relays, player lists, teleport
 * suggestions. Then a vanish addon's hidden staff stay hidden everywhere, without any addon
 * depending on it. With no {@link VisibilityProvider} registered, nobody is hidden.
 *
 * <p>Call these methods on the server thread.
 *
 * @since API version 4
 */
public interface PresenceService {
	/** Adds a provider; usually only a vanish addon has one. */
	void registerVisibilityProvider(String addonId, VisibilityProvider provider);

	/** Whether any provider hides {@code player}. */
	boolean isHidden(UUID player);

	/** Whether {@code viewer} may see {@code target}: always for unhidden targets and for oneself. */
	boolean canSee(ServerPlayer viewer, UUID target);

	/** Online players that are not hidden. */
	List<ServerPlayer> publiclyOnline(MinecraftServer server);

	/** Receives public joins and leaves from now on. */
	void addListener(PresenceListener listener);

	/**
	 * Announces a public join or leave that did not really happen, e.g. a vanish addon's fake quit.
	 * Listeners receive it with {@code real = false}. Callers are responsible for permission checks
	 * and audit records.
	 */
	void announce(UUID player, String name, PresenceListener.Kind kind);

}
