package com.mejta.mejtacore.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import com.mejta.mejtacore.addons.ClientAddonExtensions;
import com.mejta.mejtacore.api.client.MejtaClientAddon;
import com.mejta.mejtacore.commands.MejtaCommands;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.storage.RecordLogs;
import com.mejta.mejtacore.ui.ScreenOpener;

/** Client initializer: runs after every mod's {@code main} entrypoint. */
public final class MejtaCoreClient implements ClientModInitializer {
	public static final String CLIENT_ADDON_ENTRYPOINT = "mejtacore-client";

	@Override
	public void onInitializeClient() {
		MejtaCore.finishInitialization();

		for (EntrypointContainer<MejtaClientAddon> container : FabricLoader.getInstance()
				.getEntrypointContainers(CLIENT_ADDON_ENTRYPOINT, MejtaClientAddon.class)) {
			String modId = container.getProvider().getMetadata().getId();
			try {
				container.getEntrypoint().initializeClient();
			} catch (Throwable t) {
				Diagnostics.error(modId, "\"mejtacore-client\" entrypoint of mod '" + modId + "' failed", t);
			}
		}

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> MejtaCommands.register(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(ScreenOpener::tick);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> ClientAddonExtensions.reportOrphans());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			MejtaCore.configs().saveAll();
			RecordLogs.flushAll(5_000);
		});
	}
}
