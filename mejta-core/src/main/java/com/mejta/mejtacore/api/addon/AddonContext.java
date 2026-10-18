package com.mejta.mejtacore.api.addon;

import java.util.Optional;

import org.slf4j.Logger;

import com.mejta.mejtacore.api.config.ConfigHandle;

/** Passed to an addon's {@link AddonInitializer}. */
public interface AddonContext {
	AddonInfo info();

	/** The addon's loaded configuration, present if the definition declared a {@code ConfigSpec}. */
	Optional<ConfigHandle> config();

	/** Logger named {@code MejtaCore/<addon id>}. */
	Logger logger();
}
