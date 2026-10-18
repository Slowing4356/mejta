package com.mejta.mejtacore.api.client;

/**
 * Optional client entrypoint for addons. Declare it in {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": {
 *   "mejtacore-client": [ "com.example.myaddon.client.MyAddonClient" ]
 * }
 * }</pre>
 *
 * It is called once, on the client, after all addons have been initialized, which makes it the
 * simplest place to call {@link MejtaCoreClientAPI}. Menu entries and commands of an addon that
 * failed to initialize are ignored automatically.
 */
@FunctionalInterface
public interface MejtaClientAddon {
	void initializeClient();
}
