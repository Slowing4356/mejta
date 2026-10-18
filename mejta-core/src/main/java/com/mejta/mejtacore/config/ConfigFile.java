package com.mejta.mejtacore.config;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.util.SafeFiles;

/**
 * JSON-backed implementation of {@link ConfigHandle}.
 *
 * <p>Unknown keys found in the file are preserved on save, so values written by a newer version
 * (or by an addon that is temporarily removed) are not lost.
 */
public final class ConfigFile implements ConfigHandle {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final String id;
	private final ConfigSpec spec;
	private final Path file;
	private final Map<String, Object> values = new LinkedHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private JsonObject unknownKeys = new JsonObject();
	private int storedVersion;
	private boolean dirty;
	private String lastWritten;

	ConfigFile(String id, ConfigSpec spec, Path file) {
		this.id = id;
		this.spec = spec;
		this.file = file;
		this.storedVersion = spec.version();
		applyDefaults();
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public ConfigSpec spec() {
		return spec;
	}

	@Override
	public Path file() {
		return file;
	}

	@Override
	public synchronized <T> T get(Setting<T> setting) {
		requireOwned(setting);
		return setting.valueClass().cast(values.get(setting.key()));
	}

	@Override
	public <T> void set(Setting<T> setting, T value) {
		requireOwned(setting);
		if (!setting.isValid(value)) {
			throw new IllegalArgumentException("Invalid value " + value + " for setting '" + setting.key() + "' in config '" + id + "'");
		}
		put(setting, value);
	}

	@Override
	public <T> boolean trySet(Setting<T> setting, T value) {
		requireOwned(setting);
		if (!setting.isValid(value)) {
			return false;
		}
		put(setting, value);
		return true;
	}

	@Override
	public <T> void reset(Setting<T> setting) {
		requireOwned(setting);
		put(setting, setting.defaultValue());
	}

	@Override
	public void resetAll() {
		boolean changed = false;
		synchronized (this) {
			for (Setting<?> setting : spec.settings()) {
				Object old = values.put(setting.key(), setting.defaultValue());
				changed |= !Objects.equals(old, setting.defaultValue());
			}
			dirty |= changed;
		}
		if (changed) {
			fireChanged();
		}
	}

	@Override
	public synchronized boolean isDirty() {
		return dirty;
	}

	@Override
	public synchronized void save() {
		if (!dirty) {
			return;
		}
		String content = serialize();
		if (content.equals(lastWritten)) {
			dirty = false;
			return;
		}
		try {
			SafeFiles.writeAtomically(file, content);
			lastWritten = content;
			dirty = false;
			MejtaLog.debug("Saved config '{}' to {}", id, file);
		} catch (IOException e) {
			Diagnostics.error("config:" + id, "Could not save " + file, e);
		}
	}

	@Override
	public void reload() {
		load();
		fireChanged();
	}

	@Override
	public void addChangeListener(Runnable listener) {
		listeners.add(Objects.requireNonNull(listener));
	}

	/** Loads from disk. Never throws: problems are reported and defaults are used. */
	synchronized void load() {
		applyDefaults();
		unknownKeys = new JsonObject();
		storedVersion = spec.version();
		lastWritten = null;
		dirty = false;

		if (!Files.exists(file)) {
			MejtaLog.debug("Config '{}' not found, creating {}", id, file);
			dirty = true;
			save();
			return;
		}

		String content;
		JsonObject json;
		try {
			content = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(content);
			if (!parsed.isJsonObject()) {
				throw new JsonParseException("top-level value is not a JSON object");
			}
			json = parsed.getAsJsonObject();
		} catch (IOException | JsonParseException | IllegalStateException e) {
			recoverFromBrokenFile("could not be read: " + e.getMessage());
			return;
		}
		lastWritten = content;

		int fileVersion = 1;
		JsonElement versionElement = json.get(ConfigSpec.VERSION_KEY);
		if (versionElement != null && versionElement.isJsonPrimitive() && versionElement.getAsJsonPrimitive().isNumber()) {
			fileVersion = versionElement.getAsInt();
		} else {
			dirty = true;
		}

		if (fileVersion < spec.version()) {
			try {
				for (int v = fileVersion; v < spec.version(); v++) {
					final int from = v;
					spec.migrationFrom(v).ifPresent(m -> m.migrate(new JsonConfigData(json, from)));
				}
				MejtaLog.CORE.info("Migrated config '{}' from version {} to {}", id, fileVersion, spec.version());
			} catch (RuntimeException e) {
				Diagnostics.error("config:" + id, "Migration from version " + fileVersion + " failed", e);
				recoverFromBrokenFile("migration failed");
				return;
			}
			dirty = true;
		} else if (fileVersion > spec.version()) {
			Diagnostics.warn("config:" + id, "File was written by a newer format (version " + fileVersion
					+ ", this build understands " + spec.version() + "). Unknown values are kept.");
			storedVersion = fileVersion;
		}

		for (Setting<?> setting : spec.settings()) {
			JsonElement element = json.get(setting.key());
			Object decoded = decode(setting, element);
			if (decoded == null || !setting.isValid(decoded)) {
				if (element != null) {
					Diagnostics.warn("config:" + id, "Invalid value " + element + " for '" + setting.key()
							+ "', using default " + setting.defaultValue());
				}
				values.put(setting.key(), setting.defaultValue());
				dirty = true;
			} else {
				values.put(setting.key(), decoded);
			}
		}

		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			if (!entry.getKey().equals(ConfigSpec.VERSION_KEY) && spec.setting(entry.getKey()).isEmpty()) {
				unknownKeys.add(entry.getKey(), entry.getValue());
			}
		}

		if (dirty) {
			save();
		}
	}

	private void recoverFromBrokenFile(String reason) {
		try {
			Path backup = SafeFiles.backupBroken(file);
			Diagnostics.error("config:" + id, file.getFileName() + " " + reason + ". Backed up to "
					+ backup.getFileName() + " and reset to defaults.");
		} catch (IOException e) {
			Diagnostics.error("config:" + id, file.getFileName() + " " + reason + " and could not be backed up", e);
		}
		applyDefaults();
		unknownKeys = new JsonObject();
		lastWritten = null;
		dirty = true;
		save();
	}

	private <T> void put(Setting<T> setting, T value) {
		boolean changed;
		synchronized (this) {
			Object old = values.put(setting.key(), value);
			changed = !Objects.equals(old, value);
			dirty |= changed;
		}
		if (changed) {
			fireChanged();
		}
	}

	private void fireChanged() {
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				MejtaLog.CORE.error("Config change listener for '{}' failed", id, e);
			}
		}
	}

	private void requireOwned(Setting<?> setting) {
		if (!spec.contains(setting)) {
			throw new IllegalArgumentException("Setting '" + setting.key() + "' is not part of config '" + id + "'");
		}
	}

	private void applyDefaults() {
		values.clear();
		for (Setting<?> setting : spec.settings()) {
			values.put(setting.key(), setting.defaultValue());
		}
	}

	private String serialize() {
		JsonObject out = new JsonObject();
		out.addProperty(ConfigSpec.VERSION_KEY, storedVersion);
		for (Setting<?> setting : spec.settings()) {
			out.add(setting.key(), encode(values.get(setting.key())));
		}
		for (Map.Entry<String, JsonElement> entry : unknownKeys.entrySet()) {
			out.add(entry.getKey(), entry.getValue());
		}
		return GSON.toJson(out) + System.lineSeparator();
	}

	private static JsonElement encode(Object value) {
		return switch (value) {
			case Boolean b -> new JsonPrimitive(b);
			case Number n -> new JsonPrimitive(n);
			case Enum<?> e -> new JsonPrimitive(e.name());
			default -> new JsonPrimitive(String.valueOf(value));
		};
	}

	private static Object decode(Setting<?> setting, JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive p = element.getAsJsonPrimitive();
		try {
			return switch (setting.type()) {
				case BOOLEAN -> p.isBoolean() ? p.getAsBoolean() : null;
				case INTEGER -> {
					if (!p.isNumber()) {
						yield null;
					}
					BigDecimal number = p.getAsBigDecimal();
					yield number.stripTrailingZeros().scale() > 0 ? null : number.intValueExact();
				}
				case DECIMAL -> p.isNumber() ? p.getAsDouble() : null;
				case STRING -> p.isString() ? p.getAsString() : null;
				case ENUM -> !p.isString() ? null
						: setting.valueClass() == String.class ? findOption(setting, p.getAsString())
						: findEnum(setting.valueClass(), p.getAsString());
			};
		} catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
	}

	private static Object findOption(Setting<?> setting, String name) {
		for (String option : setting.options()) {
			if (option.equalsIgnoreCase(name)) {
				return option;
			}
		}
		return null;
	}

	private static Object findEnum(Class<?> enumClass, String name) {
		for (Object constant : enumClass.getEnumConstants()) {
			if (((Enum<?>) constant).name().equalsIgnoreCase(name)) {
				return constant;
			}
		}
		return null;
	}
}
