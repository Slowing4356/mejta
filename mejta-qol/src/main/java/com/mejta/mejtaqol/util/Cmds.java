package com.mejta.mejtaqol.util;

import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.teleport.Teleports;

/** Command helpers shared by MejtaQoL's features. */
public final class Cmds {
	private Cmds() {
	}

	/**
	 * A command is listed and usable only while its feature is on and the source has the permission.
	 * MejtaQoL re-sends the command list to every player when a setting changes.
	 */
	public static Predicate<CommandSourceStack> available(Setting<Boolean> enabled, MejtaPermission permission) {
		return source -> MejtaQoL.config() != null && MejtaQoL.config().get(enabled) && MejtaCoreAPI.hasPermission(source, permission);
	}

	public static Predicate<CommandSourceStack> available(Teleports.Feature feature) {
		return available(feature.enabled(), feature.permission());
	}
}
