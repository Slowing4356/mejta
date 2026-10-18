package com.mejta.mejtacore.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Schema of a configuration file: its format version, settings and migrations.
 *
 * <pre>{@code
 * public static final ConfigSpec SPEC = ConfigSpec.builder(2)
 *     .add(ENABLED, RADIUS, MODE)
 *     .migration(1, data -> data.rename("range", "radius"))   // 1 -> 2
 *     .build();
 * }</pre>
 */
public final class ConfigSpec {
	/** JSON key holding the file format version. */
	public static final String VERSION_KEY = "config_version";

	private final int version;
	private final Map<String, Setting<?>> settings;
	private final Map<Integer, ConfigMigration> migrations;

	private ConfigSpec(Builder b) {
		this.version = b.version;
		this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(b.settings));
		this.migrations = Collections.unmodifiableMap(new TreeMap<>(b.migrations));
	}

	/** @param version current format version (start at 1; bump when settings are renamed or reinterpreted) */
	public static Builder builder(int version) {
		return new Builder(version);
	}

	public int version() {
		return version;
	}

	/** Settings in declaration order. */
	public List<Setting<?>> settings() {
		return List.copyOf(settings.values());
	}

	public Optional<Setting<?>> setting(String key) {
		return Optional.ofNullable(settings.get(key));
	}

	public boolean contains(Setting<?> setting) {
		return settings.get(setting.key()) == setting;
	}

	/** Migration that upgrades data from {@code fromVersion} to {@code fromVersion + 1}. */
	public Optional<ConfigMigration> migrationFrom(int fromVersion) {
		return Optional.ofNullable(migrations.get(fromVersion));
	}

	public static final class Builder {
		private final int version;
		private final Map<String, Setting<?>> settings = new LinkedHashMap<>();
		private final Map<Integer, ConfigMigration> migrations = new TreeMap<>();

		private Builder(int version) {
			if (version < 1) {
				throw new IllegalArgumentException("Config version must be >= 1");
			}
			this.version = version;
		}

		public Builder add(Setting<?>... toAdd) {
			for (Setting<?> setting : toAdd) {
				Objects.requireNonNull(setting, "setting");
				if (settings.putIfAbsent(setting.key(), setting) != null) {
					throw new IllegalArgumentException("Duplicate setting key '" + setting.key() + "'");
				}
			}
			return this;
		}

		/** Registers the migration from {@code fromVersion} to {@code fromVersion + 1}. */
		public Builder migration(int fromVersion, ConfigMigration migration) {
			if (fromVersion < 1 || fromVersion >= version) {
				throw new IllegalArgumentException("Migration source version " + fromVersion + " must be in [1, " + (version - 1) + "]");
			}
			if (migrations.putIfAbsent(fromVersion, Objects.requireNonNull(migration)) != null) {
				throw new IllegalArgumentException("Duplicate migration from version " + fromVersion);
			}
			return this;
		}

		public ConfigSpec build() {
			return new ConfigSpec(this);
		}
	}
}
