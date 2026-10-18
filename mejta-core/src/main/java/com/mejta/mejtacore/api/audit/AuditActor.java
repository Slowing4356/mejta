package com.mejta.mejtacore.api.audit;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who performed an audited action. For command sources use {@code MejtaCoreAPI.actor(source)}.
 *
 * @param name display name: a player name, {@code Server} for the console, or a description
 * @param uuid the player's UUID, if the actor is a player
 * @param kind what kind of actor this is
 * @since API version 4
 */
public record AuditActor(String name, Optional<UUID> uuid, Kind kind) {
	public enum Kind {
		/** An online player (by command, menu or item). */
		PLAYER,
		/** The server console, RCON, command blocks or functions. */
		SERVER,
		/** The addon itself, e.g. restoring state after a restart. */
		SYSTEM,
		/** Someone outside the game, e.g. through a chat integration. */
		REMOTE
	}

	public AuditActor {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(uuid, "uuid");
		Objects.requireNonNull(kind, "kind");
	}

	public static AuditActor player(String name, UUID uuid) {
		return new AuditActor(name, Optional.of(uuid), Kind.PLAYER);
	}

	public static AuditActor server(String name) {
		return new AuditActor(name, Optional.empty(), Kind.SERVER);
	}

	/** The addon acting on its own, e.g. {@code system("mejtautils")}. */
	public static AuditActor system(String addonId) {
		return new AuditActor(addonId, Optional.empty(), Kind.SYSTEM);
	}
}
