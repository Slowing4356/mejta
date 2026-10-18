package com.mejta.mejtacore.config;

import java.util.Optional;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;

/**
 * Applies one edit to a config, with full validation. Shared by remote editing and commands.
 *
 * <p>Result messages never contain the value of a sensitive setting ({@link SettingText#isSensitive}),
 * because callers show them to the editor, log them and publish them.
 */
public final class ConfigEditing {
	public enum Op {
		SET,
		RESET,
		RESET_ALL
	}

	/** @param message what changed, or why nothing did */
	public record Result(boolean ok, String message) {
		static Result fail(String message) {
			return new Result(false, message);
		}
	}

	private ConfigEditing() {
	}

	/**
	 * @param allowSensitive whether sensitive settings may be changed by this edit. {@code false} for
	 *                       edits from a client's Server Settings menu, which never sees those settings;
	 *                       {@code RESET_ALL} then leaves them unchanged.
	 */
	public static Result apply(ConfigHandle handle, Op op, String key, String text, boolean allowSensitive) {
		if (op == Op.RESET_ALL) {
			int skipped = 0;
			for (Setting<?> setting : handle.spec().settings()) {
				if (allowSensitive || !SettingText.isSensitive(setting)) {
					handle.reset(setting);
				} else {
					skipped++;
				}
			}
			handle.save();
			return new Result(true, handle.id() + ": all settings reset to defaults"
					+ (skipped > 0 ? " (" + skipped + " secret setting(s) kept)" : ""));
		}
		Optional<Setting<?>> found = handle.spec().setting(key);
		if (found.isEmpty()) {
			return Result.fail("Unknown setting '" + key + "' in " + handle.id());
		}
		Setting<?> setting = found.get();
		boolean sensitive = SettingText.isSensitive(setting);
		if (sensitive && !allowSensitive) {
			return Result.fail(key + " is a secret setting; change it with /mejtaadmin config set (console recommended)");
		}
		if (op == Op.RESET) {
			handle.reset(setting);
			handle.save();
			return new Result(true, handle.id() + ": " + key + " reset to " + SettingText.display(setting, setting.defaultValue()));
		}
		Object value = SettingText.parse(setting, text);
		if (value == null) {
			String expected = switch (setting.type()) {
				case BOOLEAN -> "true or false";
				case INTEGER -> "a whole number";
				case DECIMAL -> "a number";
				case STRING -> "text";
				case ENUM -> "one of " + String.join(", ", setting.options());
			};
			return Result.fail(sensitive ? "The value is not valid for " + key + ": expected " + expected
					: "'" + text + "' is not valid for " + key + ": expected " + expected);
		}
		if (!setting.isValid(value)) {
			if (sensitive) {
				return Result.fail("The value is not allowed for " + key + " (value hidden)");
			}
			String range = setting.min() != null ? " (allowed: " + setting.min() + " to " + setting.max() + ")" : "";
			return Result.fail("'" + text + "' is not allowed for " + key + range);
		}
		set(handle, setting, value);
		handle.save();
		return new Result(true, sensitive
				? handle.id() + ": " + key + " changed (value hidden)"
				: handle.id() + ": " + key + " = " + SettingText.format(setting, value));
	}

	@SuppressWarnings("unchecked")
	private static <T> void set(ConfigHandle handle, Setting<T> setting, Object value) {
		handle.set(setting, (T) value);
	}
}
