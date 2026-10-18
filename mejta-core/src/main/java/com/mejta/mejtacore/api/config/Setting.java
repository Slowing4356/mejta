package com.mejta.mejtacore.api.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A typed, immutable configuration setting with a default value and validation.
 * Declare settings as constants and add them to a {@link ConfigSpec}:
 *
 * <pre>{@code
 * public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
 *     .describedAs("Turns the feature on or off");
 * public static final Setting<Integer> RADIUS = Setting.integer("radius", 8, 1, 64);
 * public static final Setting<Mode> MODE = Setting.enumeration("mode", Mode.NORMAL);
 * }</pre>
 *
 * @param <T> value type: {@link Boolean}, {@link Integer}, {@link Double}, {@link String} or an enum;
 *            {@link #choice} settings are {@link SettingType#ENUM} with {@link String} values
 */
public final class Setting<T> {
	/** Setting keys: lowercase letters, digits, {@code _}, {@code -} and {@code .}; max 64 chars. */
	public static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

	private final String key;
	private final SettingType type;
	private final Class<T> valueClass;
	private final T defaultValue;
	private final Number min;
	private final Number max;
	private final Predicate<T> validator;
	private final String description;
	private final String displayName;
	private final List<String> options;
	private final boolean secret;
	private final boolean experimental;

	private Setting(String key, SettingType type, Class<T> valueClass, T defaultValue, Number min, Number max,
			Predicate<T> validator, String description, String displayName, List<String> options, boolean secret,
			boolean experimental) {
		this.key = key;
		this.type = type;
		this.valueClass = valueClass;
		this.defaultValue = defaultValue;
		this.min = min;
		this.max = max;
		this.validator = validator;
		this.description = description;
		this.displayName = displayName;
		this.options = options;
		this.secret = secret;
		this.experimental = experimental;
	}

	private Setting<T> copy(Predicate<T> validator, String description, String displayName, boolean secret, boolean experimental) {
		return new Setting<>(key, type, valueClass, defaultValue, min, max, validator, description, displayName, options, secret,
				experimental);
	}

	private static <T> Setting<T> create(String key, SettingType type, Class<T> valueClass, T def, Number min, Number max,
			List<String> options) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(def, "default value");
		if (!KEY_PATTERN.matcher(key).matches()) {
			throw new IllegalArgumentException("Invalid setting key '" + key + "': must match " + KEY_PATTERN.pattern());
		}
		if (key.equals(ConfigSpec.VERSION_KEY)) {
			throw new IllegalArgumentException("Setting key '" + key + "' is reserved");
		}
		Setting<T> setting = new Setting<>(key, type, valueClass, def, min, max, v -> true, "", prettify(key), options, false, false);
		if (!setting.isValid(def)) {
			throw new IllegalArgumentException("Default value " + def + " of setting '" + key + "' is out of range");
		}
		return setting;
	}

	public static Setting<Boolean> bool(String key, boolean defaultValue) {
		return create(key, SettingType.BOOLEAN, Boolean.class, defaultValue, null, null, List.of());
	}

	public static Setting<Integer> integer(String key, int defaultValue, int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException("min > max for setting '" + key + "'");
		}
		return create(key, SettingType.INTEGER, Integer.class, defaultValue, min, max, List.of());
	}

	public static Setting<Integer> integer(String key, int defaultValue) {
		return integer(key, defaultValue, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public static Setting<Double> decimal(String key, double defaultValue, double min, double max) {
		if (min > max || Double.isNaN(min) || Double.isNaN(max)) {
			throw new IllegalArgumentException("Invalid range for setting '" + key + "'");
		}
		return create(key, SettingType.DECIMAL, Double.class, defaultValue, min, max, List.of());
	}

	public static Setting<Double> decimal(String key, double defaultValue) {
		return decimal(key, defaultValue, -Double.MAX_VALUE, Double.MAX_VALUE);
	}

	public static Setting<String> string(String key, String defaultValue) {
		return create(key, SettingType.STRING, String.class, defaultValue, null, null, List.of());
	}

	@SuppressWarnings("unchecked")
	public static <E extends Enum<E>> Setting<E> enumeration(String key, E defaultValue) {
		Objects.requireNonNull(defaultValue, "default value");
		List<String> names = new ArrayList<>();
		for (E constant : defaultValue.getDeclaringClass().getEnumConstants()) {
			names.add(constant.name());
		}
		return create(key, SettingType.ENUM, (Class<E>) defaultValue.getDeclaringClass(), defaultValue, null, null, List.copyOf(names));
	}

	/**
	 * A choice between fixed text options, stored like an enum setting. Use it when the options are
	 * not known as a Java enum, e.g. settings described by a server to a client.
	 *
	 * @since API version 3
	 */
	public static Setting<String> choice(String key, String defaultValue, List<String> options) {
		Objects.requireNonNull(options, "options");
		if (options.isEmpty() || options.stream().anyMatch(o -> o == null || o.isBlank())) {
			throw new IllegalArgumentException("Choice setting '" + key + "' needs non-blank options");
		}
		return create(key, SettingType.ENUM, String.class, defaultValue, null, null, List.copyOf(options));
	}

	/** Returns a copy with the given description (shown as tooltip in the settings menu). */
	public Setting<T> describedAs(String description) {
		return copy(validator, Objects.requireNonNull(description), displayName, secret, experimental);
	}

	/** Returns a copy with a custom display name. Defaults to the key in title case ({@code menu_rows} -> {@code Menu Rows}). */
	public Setting<T> named(String displayName) {
		Objects.requireNonNull(displayName, "displayName");
		return copy(validator, description, displayName, secret, experimental);
	}

	/** Returns a copy with an extra validation rule. Invalid values loaded from disk are replaced by the default. */
	public Setting<T> validatedBy(Predicate<T> rule) {
		Objects.requireNonNull(rule, "rule");
		Setting<T> copy = copy(validator.and(rule), description, displayName, secret, experimental);
		if (!copy.isValid(defaultValue)) {
			throw new IllegalArgumentException("Default value of setting '" + key + "' fails its validator");
		}
		return copy;
	}

	/**
	 * Returns a copy whose value is a secret (a token, password or webhook URL). Only {@code STRING}
	 * settings can be secret. MejtaCore then:
	 * <ul>
	 * <li>stores the value in {@code config/mejta/secrets/<id>.json}, not in the normal config file,
	 * readable only by the server's own user where the file system supports that;</li>
	 * <li>never sends it to clients (Server Settings leaves it out);</li>
	 * <li>shows only {@code (set)} or {@code (not set)} in menus, commands, logs and notifications;</li>
	 * <li>lets only holders of {@code mejtacore.config.secrets} (and the console) change it.</li>
	 * </ul>
	 *
	 * @since API version 4
	 */
	public Setting<T> secret() {
		if (type != SettingType.STRING) {
			throw new IllegalStateException("Only string settings can be secret: '" + key + "'");
		}
		return copy(validator, description, displayName, true, experimental);
	}

	/**
	 * Returns a copy marked as experimental: menus and {@code /mejtaadmin config get} label it, so
	 * server owners know the feature may still change.
	 *
	 * @since API version 4
	 */
	public Setting<T> experimental() {
		return copy(validator, description, displayName, secret, true);
	}

	/** @since API version 4 */
	public boolean isSecret() {
		return secret;
	}

	/** @since API version 4 */
	public boolean isExperimental() {
		return experimental;
	}

	/** Checks type, range and custom validation. */
	public boolean isValid(Object value) {
		if (!valueClass.isInstance(value)) {
			return false;
		}
		T typed = valueClass.cast(value);
		switch (type) {
			case INTEGER -> {
				int v = (Integer) typed;
				if (v < min.intValue() || v > max.intValue()) {
					return false;
				}
			}
			case DECIMAL -> {
				double v = (Double) typed;
				if (Double.isNaN(v) || Double.isInfinite(v) || v < min.doubleValue() || v > max.doubleValue()) {
					return false;
				}
			}
			case ENUM -> {
				if (typed instanceof String text && !options.contains(text)) {
					return false;
				}
			}
			default -> {
			}
		}
		return validator.test(typed);
	}

	public String key() {
		return key;
	}

	public SettingType type() {
		return type;
	}

	public Class<T> valueClass() {
		return valueClass;
	}

	public T defaultValue() {
		return defaultValue;
	}

	/** Lower bound for {@code INTEGER}/{@code DECIMAL} settings, otherwise {@code null}. */
	public Number min() {
		return min;
	}

	/** Upper bound for {@code INTEGER}/{@code DECIMAL} settings, otherwise {@code null}. */
	public Number max() {
		return max;
	}

	public String description() {
		return description;
	}

	public String displayName() {
		return displayName;
	}

	/**
	 * For {@code ENUM} settings: the allowed values by name, in order (enum constant names, or the
	 * options of a {@link #choice} setting). Empty for other types.
	 *
	 * @since API version 3
	 */
	public List<String> options() {
		return options;
	}

	private static String prettify(String key) {
		StringBuilder out = new StringBuilder();
		for (String part : key.split("[_.-]")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return out.toString();
	}

	@Override
	public String toString() {
		return "Setting[" + key + ":" + type + (secret ? " secret" : "") + "]";
	}
}
