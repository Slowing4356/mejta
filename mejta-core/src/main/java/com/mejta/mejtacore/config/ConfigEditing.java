package com.mejta.mejtacore.config;

import java.util.Optional;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;

/** Applies one edit to a config, with full validation. Shared by remote editing and commands. */
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

	public static Result apply(ConfigHandle handle, Op op, String key, String text) {
		if (op == Op.RESET_ALL) {
			handle.resetAll();
			handle.save();
			return new Result(true, handle.id() + ": all settings reset to defaults");
		}
		Optional<Setting<?>> found = handle.spec().setting(key);
		if (found.isEmpty()) {
			return Result.fail("Unknown setting '" + key + "' in " + handle.id());
		}
		Setting<?> setting = found.get();
		if (op == Op.RESET) {
			handle.reset(setting);
			handle.save();
			return new Result(true, handle.id() + ": " + key + " reset to " + SettingText.format(setting, setting.defaultValue()));
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
			return Result.fail("'" + text + "' is not valid for " + key + ": expected " + expected);
		}
		if (!setting.isValid(value)) {
			String range = setting.min() != null ? " (allowed: " + setting.min() + " to " + setting.max() + ")" : "";
			return Result.fail("'" + text + "' is not allowed for " + key + range);
		}
		set(handle, setting, value);
		handle.save();
		return new Result(true, handle.id() + ": " + key + " = " + SettingText.format(setting, value));
	}

	@SuppressWarnings("unchecked")
	private static <T> void set(ConfigHandle handle, Setting<T> setting, Object value) {
		handle.set(setting, (T) value);
	}
}
