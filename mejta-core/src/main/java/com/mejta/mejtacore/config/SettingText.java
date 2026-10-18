package com.mejta.mejtacore.config;

import com.mejta.mejtacore.api.config.Setting;

/** Converts setting values to and from text, for the network and for commands. */
public final class SettingText {
	private SettingText() {
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
