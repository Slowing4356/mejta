package com.mejta.mejtacore.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;

/** Owns every loaded config file. Each owner (core or addon) gets {@code <configDir>/<id>.json}. */
public final class ConfigManager {
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
		ConfigFile file = new ConfigFile(ownerId, spec, directory.resolve(ownerId + ".json"));
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
