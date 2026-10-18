package com.mejta.mejtacore.api.addon;

public enum AddonState {
	/** Registered, waiting for MejtaCore to resolve and initialize addons. */
	PENDING,
	/** Initialized successfully. */
	ACTIVE,
	/** Missing dependency, dependency cycle, failed dependency, or exception during initialization. */
	FAILED
}
