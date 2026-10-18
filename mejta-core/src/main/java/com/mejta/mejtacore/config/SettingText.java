package com.mejta.mejtacore.config;

import java.util.regex.Pattern;

import com.mejta.mejtacore.api.config.Setting;

/** Converts setting values to and from text, for the network and for commands. */
public final class SettingText {
	/**
	 * Backstop for settings that hold a secret but were not declared with {@code Setting#secret()}:
	 * their values are hidden the same way, everywhere a value would be shown.
	 */
	private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|secret|password|webhook).*");

	private SettingText() {
	}

	/** Whether the setting's value must never be shown, logged, published or sent to a client. */
	public static boolean isSensitive(Setting<?> setting) {
		return setting.isSecret() || SECRET_KEY.matcher(setting.key()).matches();
	}

	/** Whether a key looks like it holds a secret (used where only the key is known). */
	public static boolean isSensitiveKey(String key) {
		return key != null && SECRET_KEY.matcher(key).matches();
	}

	/** Text safe to show anywhere: the value, or {@code (set)}/{@code (not set)} for sensitive settings. */
	public static String display(Setting<?> setting, Object value) {
		if (isSensitive(setting)) {
			return value == null || String.valueOf(value).isEmpty() ? "(not set)" : "(set)";
		}
		return format(setting, value);
	}

	public static String format(Setting<?> setting, Object value) {
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		return String.valueOf(value);
	}

	/** The value {@code text} stands for, or {@code null} if it is not a value of the setting's type. */
	public static Object parse(Setting<?> setting, String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		try {
			return switch (setting.type()) {
				case BOOLEAN -> trimmed.equalsIgnoreCase("true") ? Boolean.TRUE
						: trimmed.equalsIgnoreCase("false") ? Boolean.FALSE : null;
				case INTEGER -> Integer.parseInt(trimmed);
				case DECIMAL -> {
					double d = Double.parseDouble(trimmed);
					yield Double.isFinite(d) ? d : null;
				}
				case STRING -> text;
				case ENUM -> option(setting, trimmed);
			};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Object option(Setting<?> setting, String name) {
		if (setting.valueClass() == String.class) {
			for (String option : setting.options()) {
				if (option.equalsIgnoreCase(name)) {
					return option;
				}
			}
			return null;
		}
		for (Object constant : setting.valueClass().getEnumConstants()) {
			if (((Enum<?>) constant).name().equalsIgnoreCase(name)) {
				return constant;
			}
		}
		return null;
	}
}
