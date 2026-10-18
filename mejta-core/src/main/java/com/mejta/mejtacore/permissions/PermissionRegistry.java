package com.mejta.mejtacore.permissions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.config.ConfigManager;
import com.mejta.mejtacore.logging.MejtaLog;

/**
 * Resolves {@link MejtaPermission}s against Minecraft's operator levels. Each declared node becomes
 * an enum setting in {@code mejtacore.permissions.json}, so server owners can change the required
 * level without code. The config ID contains a dot, so it can never collide with an addon ID.
 */
public final class PermissionRegistry {
	public static final String CONFIG_ID = "mejtacore.permissions";

	/** Core's own nodes. */
	public static final MejtaPermission ADMIN = MejtaPermission.of("mejtacore.admin", PermissionLevel.GAMEMASTERS)
			.describedAs("Use /mejtaadmin addons and /mejtaadmin config reload");
	public static final MejtaPermission CONFIG_EDIT = MejtaPermission.of("mejtacore.config.edit", PermissionLevel.ADMINS)
			.describedAs("View and change the server's settings from the MejtaCore menu or /mejtaadmin config");
	public static final MejtaPermission CONFIG_PERMISSIONS = MejtaPermission.of("mejtacore.config.permissions", PermissionLevel.OWNERS)
			.describedAs("Also change these permission levels remotely (needs mejtacore.config.edit too)");

	private static final Map<String, Setting<PermissionLevel>> SETTINGS = new LinkedHashMap<>();
	private static volatile ConfigHandle config;

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
		for (AddonInfo addon : addons) {
			for (MejtaPermission permission : addon.definition().permissions()) {
				add(permission);
			}
		}
		ConfigSpec.Builder spec = ConfigSpec.builder(1);
		SETTINGS.values().forEach(spec::add);
		config = configs.open(CONFIG_ID, spec.build());
		MejtaLog.debug("Registered {} permission node(s)", SETTINGS.size());
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

	public static boolean check(PermissionSet permissions, MejtaPermission permission) {
		PermissionLevel level = requiredLevel(permission);
		return level == PermissionLevel.ALL || permissions.hasPermission(new Permission.HasCommandLevel(level));
	}

	public static synchronized List<String> nodes() {
		return List.copyOf(SETTINGS.keySet());
	}
}
