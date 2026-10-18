package com.mejta.mejtacore.core;

import java.util.Optional;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/** Tracks the running server (dedicated or integrated) for core services that need it. */
public final class ServerHolder {
	private static volatile MinecraftServer server;

	private ServerHolder() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			if (server == s) {
				server = null;
			}
		});
	}

	public static Optional<MinecraftServer> current() {
		return Optional.ofNullable(server);
	}
}
