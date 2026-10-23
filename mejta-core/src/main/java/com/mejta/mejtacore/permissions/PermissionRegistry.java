package com.mejta.mejtacore.permissions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.rank.Rank;
import com.mejta.mejtacore.config.ConfigManager;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.ranks.Ranks;

/**
 * Resolves {@link MejtaPermission}s against Minecraft's operator levels. Each declared node becomes
 * an enum setting in {@code mejtacore.permissions.json}, so server owners can change the required
 * level without code. The config ID contains a dot, so it can never collide with an addon ID.
 *
 * <p>Each addon node also gets a setting in {@code mejtacore.rankperms.json}: the lowest {@link Rank} that has the
 * node whatever its operator level ({@code OFF}: no rank). Core's own nodes are never given by rank. Both files can
 * only be edited remotely with {@link #CONFIG_PERMISSIONS}.
 */
public final class PermissionRegistry {
	public static final String CONFIG_ID = "mejtacore.permissions";
	/** @since 1.10.0 */
	public static final String RANK_CONFIG_ID = "mejtacore.rankperms";

	/** The lowest rank that has a node; {@code OFF}: none. */
	public enum RankGrant {
		OFF,
		BRONZE,
		SILVER,
		GOLD;

		static RankGrant of(Rank rank) {
			return rank == null || rank == Rank.MEMBER ? OFF : valueOf(rank.name());
		}

		Rank rank() {
			return this == OFF ? null : Rank.valueOf(name());
		}
	}

	/** Core's own nodes. */
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtacore.admin", PermissionLevel.GAMEMASTERS)
			.describedAs("Use /mejtaadmin addons and /mejtaadmin config reload");
	public static final MejtaPermission CONFIG_EDIT = MejtaPermission.of("mejtacore.config.edit", PermissionLevel.ADMINS)
			.describedAs("View and change the server's settings from the MejtaCore menu or /mejtaadmin config");
	public static final MejtaPermission CONFIG_PERMISSIONS = MejtaPermission.of("mejtacore.config.permissions", PermissionLevel.OWNERS)
			.describedAs("Also change these permission levels remotely (needs mejtacore.config.edit too)");
	/** @since API version 4 */
	public static final MejtaPermission CONFIG_SECRETS = MejtaPermission.of("mejtacore.config.secrets", PermissionLevel.OWNERS)
			.describedAs("Change secret settings (tokens, webhook URLs) with /mejtaadmin config set (needs mejtacore.config.edit too)");
	/** @since 1.10.0 */
	public static final MejtaPermission RANKS_MANAGE = MejtaPermission.of("mejtacore.ranks.manage", PermissionLevel.ADMINS)
			.describedAs("See and set players' ranks with /mejtaadmin rank");

	private static final Map<String, Setting<PermissionLevel>> SETTINGS = new LinkedHashMap<>();
	private static final Map<String, Setting<RankGrant>> RANK_SETTINGS = new LinkedHashMap<>();
	private static volatile ConfigHandle config;
	private static volatile ConfigHandle rankConfig;

	private PermissionRegistry() {
	}

	/** Builds the permissions config from core's nodes plus every addon's declared nodes. */
	public static synchronized void initialize(ConfigManager configs, Collection<AddonInfo> addons) {
		if (config != null) {
			return;
		}
		add(ADMIN);
		add(CONFIG_EDIT);
		add(CONFIG_PERMISSIONS);
		add(CONFIG_SECRETS);
		add(RANKS_MANAGE);
		for (AddonInfo addon : addons) {
			for (MejtaPermission permission : addon.definition().permissions()) {
				add(permission);
				addRankGrant(permission);
			}
		}
		ConfigSpec.Builder spec = ConfigSpec.builder(1);
		SETTINGS.values().forEach(spec::add);
		config = configs.open(CONFIG_ID, spec.build());
		ConfigSpec.Builder rankSpec = ConfigSpec.builder(1);
		RANK_SETTINGS.values().forEach(rankSpec::add);
		rankConfig = configs.open(RANK_CONFIG_ID, rankSpec.build());
		MejtaLog.debug("Registered {} permission node(s)", SETTINGS.size());
	}

	private static void addRankGrant(MejtaPermission permission) {
		if (RANK_SETTINGS.containsKey(permission.node())) {
			return;
		}
		String description = permission.description().isEmpty()
				? "Lowest rank that has it, whatever the operator level (OFF: no rank)"
				: permission.description() + " (lowest rank that has it; OFF: no rank)";
		RANK_SETTINGS.put(permission.node(), Setting.enumeration(permission.node(), RankGrant.of(permission.defaultRank()))
				.describedAs(description)
				.named(permission.node()));
	}

	/** Whether the config may only be edited remotely with {@link #CONFIG_PERMISSIONS}. */
	public static boolean isPermissionsConfig(String configId) {
		return CONFIG_ID.equals(configId) || RANK_CONFIG_ID.equals(configId);
	}

	private static void add(MejtaPermission permission) {
		if (SETTINGS.containsKey(permission.node())) {
			return;
		}
		String description = permission.description().isEmpty()
				? "Required operator level"
				: permission.description() + " (required operator level)";
		SETTINGS.put(permission.node(), Setting.enumeration(permission.node(), permission.defaultLevel())
				.describedAs(description)
				.named(permission.node()));
	}

	/** The level currently required for {@code permission}: the configured override, else its default. */
	public static PermissionLevel requiredLevel(MejtaPermission permission) {
		ConfigHandle handle = config;
		Setting<PermissionLevel> setting;
		synchronized (PermissionRegistry.class) {
			setting = SETTINGS.get(permission.node());
		}
		if (handle == null || setting == null) {
			return permission.defaultLevel();
		}
		return handle.get(setting);
	}

	/**
	 * The lowest rank that has {@code permission} whatever the operator level, or {@code null} if no rank does. Core's
	 * own nodes never have one.
	 */
	public static Rank requiredRank(MejtaPermission permission) {
		ConfigHandle handle = rankConfig;
		Setting<RankGrant> setting;
		synchronized (PermissionRegistry.class) {
			setting = RANK_SETTINGS.get(permission.node());
		}
		if (handle == null || setting == null) {
			return permission.owner().equals("mejtacore") ? null : permission.defaultRank();
		}
		return handle.get(setting).rank();
	}

	/** By operator level only. */
	public static boolean check(PermissionSet permissions, MejtaPermission permission) {
		PermissionLevel level = requiredLevel(permission);
		return level == PermissionLevel.ALL || permissions.hasPermission(new Permission.HasCommandLevel(level));
	}

	/** By operator level, or by the rank of {@code player} ({@code null}: console, command block). */
	public static boolean check(PermissionSet permissions, UUID player, MejtaPermission permission) {
		return check(permissions, permission) || byRank(player, permission);
	}

	/** Whether the rank of {@code player} alone gives {@code permission}. */
	public static boolean byRank(UUID player, MejtaPermission permission) {
		if (player == null) {
			return false;
		}
		Rank required = requiredRank(permission);
		return required != null && Ranks.get().rank(player).atLeast(required);
	}

	/** Compares a bare operator level (e.g. of an offline player) with the level {@code permission} requires. */
	public static boolean checkLevel(PermissionLevel actual, MejtaPermission permission) {
		PermissionLevel required = requiredLevel(permission);
		// PermissionLevel is declared from ALL (0) to OWNERS (4).
		return required == PermissionLevel.ALL || actual.ordinal() >= required.ordinal();
	}

	public static synchronized List<String> nodes() {
		return List.copyOf(SETTINGS.keySet());
	}
}
