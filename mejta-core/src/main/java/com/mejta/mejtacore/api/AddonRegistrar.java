package com.mejta.mejtacore.api;

import com.mejta.mejtacore.api.addon.AddonDefinition;

/** Passed to {@link MejtaAddon#registerAddons(AddonRegistrar)}. */
@FunctionalInterface
public interface AddonRegistrar {
	/**
	 * Registers an addon definition.
	 *
	 * @return {@code true} if accepted, {@code false} if rejected (the reason is logged)
	 */
	boolean register(AddonDefinition definition);
}
