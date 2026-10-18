package com.mejta.mejtacore.api.config;

/**
 * Upgrades raw configuration data by one format version. Values that are still missing or invalid
 * after all migrations are replaced by their defaults, so a migration only needs to handle renamed
 * or reinterpreted settings.
 */
@FunctionalInterface
public interface ConfigMigration {
	void migrate(ConfigData data);
}
