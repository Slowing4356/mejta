package com.mejta.mejtacore.api.config;

import java.util.Optional;
import java.util.Set;

/**
 * Raw, untyped view of a configuration file used by {@link ConfigMigration}s.
 * Values are {@link Boolean}, {@link Number} or {@link String}.
 */
public interface ConfigData {
	/** The format version the data is currently at (before this migration step). */
	int version();

	Set<String> keys();

	boolean has(String key);

	/** The raw value, or empty if absent or not a primitive. */
	Optional<Object> get(String key);

	/** Sets a raw value; {@code value} must be a Boolean, Number or String. */
	void set(String key, Object value);

	/** Moves a value to a new key (no-op if {@code from} is absent; overwrites {@code to}). */
	void rename(String from, String to);

	void remove(String key);
}
