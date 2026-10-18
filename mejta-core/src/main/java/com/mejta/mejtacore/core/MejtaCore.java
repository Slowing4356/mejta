package com.mejta.mejtacore.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import com.mejta.mejtacore.addons.AddonRegistry;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.commands.ServerCommands;
import com.mejta.mejtacore.config.ConfigManager;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.notify.InGameProvider;
import com.mejta.mejtacore.notify.NotificationBus;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.storage.RecordLogs;

/**
 * Common initializer. Loads the core config and collects {@code "mejtacore"} entrypoints.
 * Addons are resolved and initialized by {@link #finishInitialization()}, which the client or
 * dedicated-server initializer calls once every mod's {@code main} entrypoint has run.
 */
public final class MejtaCore implements ModInitializer {
	public static final String MOD_ID = "mejtacore";
	public static final String ADDON_ENTRYPOINT = "mejtacore";

	private static ConfigManager configs;
	private static ConfigHandle coreConfig;
	private static String version = "unknown";

	@Override
	public void onInitialize() {
		FabricLoader loader = FabricLoader.getInstance();
		version = loader.getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");

		configs = new ConfigManager(loader.getConfigDir().resolve("mejta"));
		coreConfig = configs.open(MOD_ID, CoreSettings.SPEC);
		MejtaLog.setDebug(coreConfig.get(CoreSettings.DEBUG_LOGGING));
		coreConfig.addChangeListener(() -> MejtaLog.setDebug(coreConfig.get(CoreSettings.DEBUG_LOGGING)));

		MejtaLog.CORE.info("MejtaCore {} starting (config: {})", version, configs.directory());

		ServerHolder.register();
		NotificationBus.get().registerProvider(new InGameProvider());
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				ServerCommands.register(dispatcher, buildContext));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RecordLogs.flushAll(5_000));

		for (EntrypointContainer<MejtaAddon> container : loader.getEntrypointContainers(ADDON_ENTRYPOINT, MejtaAddon.class)) {
			String modId = container.getProvider().getMetadata().getId();
			try {
				container.getEntrypoint().registerAddons(def -> AddonRegistry.get().register(def, "mod '" + modId + "'"));
			} catch (Throwable t) {
				Diagnostics.error(modId, "\"mejtacore\" entrypoint of mod '" + modId + "' threw during registration", t);
			}
		}
	}

	/** Closes registration, loads the permissions config and initializes addons. Safe to call more than once. */
	public static void finishInitialization() {
		AddonRegistry.get().finalizeRegistrations(configs);
		PermissionRegistry.initialize(configs, AddonRegistry.get().all());
	}

	public static ConfigManager configs() {
		return configs;
	}

	public static ConfigHandle coreConfig() {
		return coreConfig;
	}

	public static String version() {
		return version;
	}
}
