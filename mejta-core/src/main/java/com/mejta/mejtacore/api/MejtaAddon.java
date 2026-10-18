package com.mejta.mejtacore.api;

/**
 * Entrypoint interface for addons. Declare it in your {@code fabric.mod.json}:
 *
 * <pre>{@code
 * "entrypoints": {
 *   "mejtacore": [ "com.example.myaddon.MyAddon" ]
 * }
 * }</pre>
 *
 * MejtaCore instantiates the class and calls {@link #registerAddons(AddonRegistrar)} during its
 * own initialization, so the addon's load order relative to MejtaCore does not matter.
 */
@FunctionalInterface
public interface MejtaAddon {
	void registerAddons(AddonRegistrar registrar);
}
