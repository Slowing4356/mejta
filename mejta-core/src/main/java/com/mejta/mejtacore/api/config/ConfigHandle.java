package com.mejta.mejtacore.api.config;

import java.nio.file.Path;

/**
 * Access to one loaded configuration file. All methods are thread-safe.
 *
 * <p>{@link #set} changes the in-memory value and marks the file dirty; nothing is written until
 * {@link #save()} is called. MejtaCore also saves dirty configs automatically when its menu closes
 * and when the game shuts down. Saving an unchanged config does not touch the disk.
 */
public interface ConfigHandle {
	/** Owner ID ({@code mejtacore} or the addon ID). */
	String id();

	ConfigSpec spec();

	/** The JSON file backing this config. */
	Path file();

	<T> T get(Setting<T> setting);

	/**
	 * Sets a value.
	 *
	 * @throws IllegalArgumentException if the setting is not part of this spec or the value is invalid
	 */
	<T> void set(Setting<T> setting, T value);

	/** Like {@link #set} but returns {@code false} instead of throwing for an invalid value. */
	<T> boolean trySet(Setting<T> setting, T value);

	<T> void reset(Setting<T> setting);

	void resetAll();

	boolean isDirty();

	/** Writes the file if it has unsaved changes. Errors are logged, not thrown. */
	void save();

	/**
	 * Re-reads the file from disk, discarding unsaved changes. Missing or invalid values fall back
	 * to defaults; a corrupt file is backed up and replaced.
	 */
	void reload();

	/** Called (on the thread that made the change) after any {@link #set}, reset or reload. */
	void addChangeListener(Runnable listener);
}
