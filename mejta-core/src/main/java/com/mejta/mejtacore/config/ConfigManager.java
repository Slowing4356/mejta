package com.mejta.mejtacore.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;

/**
 * Owns every loaded config file. Each owner (core or addon) gets {@code <configDir>/<id>.json}, plus
 * {@code <configDir>/secrets/<id>.json} if it declares secret settings.
 */
public final class ConfigManager {
	/** Subfolder of the config directory holding secret values ({@code Setting#secret()}). */
	public static final String SECRETS_DIRECTORY = "secrets";

	private final Path directory;
	private final Map<String, ConfigFile> files = new LinkedHashMap<>();

	public ConfigManager(Path directory) {
		this.directory = directory;
	}

	public Path directory() {
		return directory;
	}

	/** Creates and loads the config for {@code ownerId}. */
	public synchronized ConfigHandle open(String ownerId, ConfigSpec spec) {
		if (files.containsKey(ownerId)) {
			throw new IllegalStateException("Config '" + ownerId + "' is already open");
		}
		boolean hasSecrets = spec.settings().stream().anyMatch(s -> s.isSecret());
		Path secrets = hasSecrets ? directory.resolve(SECRETS_DIRECTORY).resolve(ownerId + ".json") : null;
		ConfigFile file = new ConfigFile(ownerId, spec, directory.resolve(ownerId + ".json"), secrets);
		file.load();
		files.put(ownerId, file);
		return file;
	}

	public synchronized Optional<ConfigHandle> find(String ownerId) {
		return Optional.ofNullable(files.get(ownerId));
	}

	public synchronized List<ConfigHandle> all() {
		return new ArrayList<>(files.values());
	}

	public void saveAll() {
		for (ConfigHandle handle : all()) {
			handle.save();
		}
	}

	/** Reloads every config from disk; returns how many were reloaded. */
	public int reloadAll() {
		List<ConfigHandle> handles = all();
		for (ConfigHandle handle : handles) {
			handle.reload();
		}
		return handles.size();
	}
}
