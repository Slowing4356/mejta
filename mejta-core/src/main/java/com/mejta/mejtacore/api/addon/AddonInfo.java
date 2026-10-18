package com.mejta.mejtacore.api.addon;

import java.util.Optional;

import com.mejta.mejtacore.api.config.ConfigHandle;

/** Read-only view of a registered addon. */
public interface AddonInfo {
	String id();

	String name();

	String version();

	String description();

	/** Item ID used as icon, e.g. {@code minecraft:book}. */
	String iconItem();

	AddonState state();

	default boolean isActive() {
		return state() == AddonState.ACTIVE;
	}

	/** Why the addon failed, if {@link #state()} is {@link AddonState#FAILED}. */
	Optional<String> failureReason();

	/** Where the registration came from (entrypoint mod ID or API call), for diagnostics. */
	String source();

	/** The addon's configuration, if it declared one and it was loaded. */
	Optional<ConfigHandle> config();

	AddonDefinition definition();
}
