package com.mejta.mejtacore.api.permission;

import java.util.Objects;
import java.util.regex.Pattern;

import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.rank.Rank;

/**
 * A named server permission backed by Minecraft's built-in operator levels.
 *
 * <pre>{@code
 * public static final MejtaPermission ALERTS = MejtaPermission.of("myaddon.alerts", PermissionLevel.MODERATORS)
 *     .describedAs("Receive alerts in chat");
 * }</pre>
 *
 * Declare permissions on the addon definition ({@code AddonDefinition.Builder#permissions}). Server
 * owners can then change the required level of each node in {@code config/mejta/mejtacore.permissions.json}
 * (or the Configuration menu). Check them with {@code MejtaCoreAPI.hasPermission(...)}.
 *
 * <p>The node must start with the owning addon's ID followed by a dot, e.g. {@code myaddon.alerts}.
 *
 * <p>A node can also be given to every player from a rank up, whatever their operator level, with
 * {@link #grantedFrom}; server owners change that rank in {@code mejtacore.rankperms.json}.
 */
public final class MejtaPermission {
	/** Permission nodes: lowercase letters, digits, {@code _}, {@code -} and {@code .}; max 64 chars. */
	public static final Pattern NODE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+");

	private final String node;
	private final PermissionLevel defaultLevel;
	private final String description;
	private final Rank defaultRank;

	private MejtaPermission(String node, PermissionLevel defaultLevel, String description, Rank defaultRank) {
		this.node = node;
		this.defaultLevel = defaultLevel;
		this.description = description;
		this.defaultRank = defaultRank;
	}

	/**
	 * @param node         e.g. {@code myaddon.alerts}
	 * @param defaultLevel operator level required unless the server owner overrides it
	 */
	public static MejtaPermission of(String node, PermissionLevel defaultLevel) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(defaultLevel, "defaultLevel");
		if (node.length() > 64 || !NODE_PATTERN.matcher(node).matches()) {
			throw new IllegalArgumentException("Invalid permission node '" + node + "': must look like 'addonid.name'");
		}
		return new MejtaPermission(node, defaultLevel, "", null);
	}

	/** Returns a copy with the given description (shown in the permissions config). */
	public MejtaPermission describedAs(String description) {
		return new MejtaPermission(node, defaultLevel, Objects.requireNonNull(description, "description"), defaultRank);
	}

	/**
	 * Returns a copy that every player of {@code rank} or higher has by default, e.g. a perk command for Bronze and up.
	 * {@link Rank#MEMBER} or {@code null}: no rank grants it (the default).
	 *
	 * @since API version 8
	 */
	public MejtaPermission grantedFrom(Rank rank) {
		return new MejtaPermission(node, defaultLevel, description, rank == Rank.MEMBER ? null : rank);
	}

	public String node() {
		return node;
	}

	/** The part before the first dot: the ID of the addon that owns this permission. */
	public String owner() {
		return node.substring(0, node.indexOf('.'));
	}

	public PermissionLevel defaultLevel() {
		return defaultLevel;
	}

	public String description() {
		return description;
	}

	/** The lowest rank that has this node by default, or {@code null} if no rank does. @since API version 8 */
	public Rank defaultRank() {
		return defaultRank;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MejtaPermission other && other.node.equals(node);
	}

	@Override
	public int hashCode() {
		return node.hashCode();
	}

	@Override
	public String toString() {
		return "MejtaPermission[" + node + " >= " + defaultLevel.getSerializedName() + "]";
	}
}
