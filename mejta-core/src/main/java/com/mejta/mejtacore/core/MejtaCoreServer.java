package com.mejta.mejtacore.core;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Dedicated-server initializer: runs after every mod's {@code main} entrypoint. */
public final class MejtaCoreServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		MejtaCore.finishInitialization();
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> MejtaCore.configs().saveAll());
	}
}
