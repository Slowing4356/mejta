package com.mejta.mejtasmp.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.SmpSettings;

/**
 * Settings that are game rules: {@code sleep.percentage} ({@code players_sleeping_percentage}) and
 * {@code dimensions.locator_bar} ({@code locator_bar}). Applied when the server starts and when the setting
 * changes. A rule set to "leave as it is" is never touched, so {@code /gamerule} keeps working for it.
 */
public final class WorldRules {
	private WorldRules() {
	}

	public static void apply(MinecraftServer server, ConfigHandle config) {
		GameRules rules = server.getGameRules();
		int sleep = config.get(SmpSettings.SLEEP_PERCENTAGE);
		if (sleep >= 0 && rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE) != sleep) {
			rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, sleep, server);
		}
		Boolean locator = switch (config.get(SmpSettings.LOCATOR_BAR)) {
			case SHOWN -> true;
			case HIDDEN -> false;
			case VANILLA -> null;
		};
		if (locator != null && rules.get(GameRules.LOCATOR_BAR) != locator) {
			rules.set(GameRules.LOCATOR_BAR, locator, server);
		}
	}
}
