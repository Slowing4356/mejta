package com.mejta.mejtacore.config;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/** Human-readable names for config files in menus. */
public final class ConfigNames {
	private ConfigNames() {
	}

	public static String displayName(ConfigHandle config) {
		if (config.id().equals(MejtaCore.MOD_ID)) {
			return "MejtaCore";
		}
		if (config.id().equals(PermissionRegistry.CONFIG_ID)) {
			return "Permissions";
		}
		return MejtaCoreAPI.getAddon(config.id()).map(AddonInfo::name).orElse(config.id());
	}
}
