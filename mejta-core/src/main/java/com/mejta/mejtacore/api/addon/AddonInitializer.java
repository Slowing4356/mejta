package com.mejta.mejtacore.api.addon;

/**
 * Initialization callback of an addon. If it throws, the addon (and every addon that requires it)
 * is marked {@link AddonState#FAILED}; MejtaCore and unrelated addons keep running.
 */
@FunctionalInterface
public interface AddonInitializer {
	void onInitialize(AddonContext context) throws Exception;
}
