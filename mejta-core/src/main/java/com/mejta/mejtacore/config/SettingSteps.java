package com.mejta.mejtacore.config;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;

/**
 * One click's change to a setting (toggle, next/previous option, a step up or down), shared by the
 * menus. Pure logic: the result still goes through the normal validated edit path.
 */
public final class SettingSteps {
	/** Shift-clicks move this many steps. */
	public static final int BIG_STEP = 10;

	private SettingSteps() {
	}

	/** Whether a number setting has a range other than "any int/double". */
	public static boolean hasRealRange(Setting<?> setting) {
		return switch (setting.type()) {
			case INTEGER -> setting.min().intValue() != Integer.MIN_VALUE || setting.max().intValue() != Integer.MAX_VALUE;
			case DECIMAL -> setting.min().doubleValue() != -Double.MAX_VALUE || setting.max().doubleValue() != Double.MAX_VALUE;
			default -> false;
		};
	}

	/**
	 * The value after one click: booleans toggle, enums and choices cycle (skipping values the addon's
	 * validator refuses), numbers move one step ({@code big}: {@link #BIG_STEP} steps), clamped to the range.
	 * Returns {@code current} if nothing else is possible (at a limit, only one allowed option) and
	 * {@code null} for text settings, which cannot be stepped.
	 *
	 * @param back previous option / step down (right-click)
	 */
	public static Object next(Setting<?> setting, Object current, boolean back, boolean big) {
		return switch (setting.type()) {
			case BOOLEAN -> !Boolean.TRUE.equals(current);
			case INTEGER -> nextInteger(setting, ((Number) current).intValue(), back, big);
			case DECIMAL -> nextDecimal(setting, ((Number) current).doubleValue(), decimalStep(setting), back, big);
			case ENUM -> cycle(setting, current, back ? -1 : 1);
			case STRING -> null;
		};
	}

	public static int nextInteger(Setting<?> setting, int current, boolean back, boolean big) {
		long step = big ? BIG_STEP : 1;
		long next = (long) current + (back ? -step : step);
		return (int) Math.clamp(next, setting.min().longValue(), setting.max().longValue());
	}

	/**
	 * A step that splits the setting's range into 20 to 200 steps (a power of ten: 0.01 for 0 to 1, 0.1 for
	 * 0.1 to 5, 1 for 0 to 100); 0.1 for a decimal without a range.
	 */
	public static double decimalStep(Setting<?> setting) {
		if (!hasRealRange(setting)) {
			return 0.1;
		}
		double span = setting.max().doubleValue() - setting.min().doubleValue();
		if (!Double.isFinite(span) || span <= 0) {
			return 0.1;
		}
		int exponent = (int) Math.ceil(Math.log10(span / 200));
		return BigDecimal.ONE.scaleByPowerOfTen(Math.clamp(exponent, -6, 6)).doubleValue();
	}

	/** Exact decimal arithmetic, so 1.1 + 0.1 is 1.2 and not 1.2000000000000002. */
	public static double nextDecimal(Setting<?> setting, double current, double step, boolean back, boolean big) {
		BigDecimal delta = BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(big ? BIG_STEP : 1));
		double next = BigDecimal.valueOf(current).add(back ? delta.negate() : delta).doubleValue();
		return Math.clamp(next, setting.min().doubleValue(), setting.max().doubleValue());
	}

	/** The next ({@code direction} 1) or previous (-1) option the setting accepts, wrapping around. */
	public static Object cycle(Setting<?> setting, Object current, int direction) {
		List<?> values = setting.valueClass() == String.class
				? setting.options()
				: Arrays.asList(setting.valueClass().getEnumConstants());
		if (values.isEmpty()) {
			return current;
		}
		int index = Math.max(0, values.indexOf(current));
		for (int i = 1; i <= values.size(); i++) {
			Object candidate = values.get(Math.floorMod(index + direction * i, values.size()));
			if (setting.isValid(candidate)) {
				return candidate;
			}
		}
		return current;
	}

	/** Why {@link #next} returned the current value: for the editor's feedback. */
	public static String unchangedReason(Setting<?> setting, Object current, boolean back) {
		if (setting.type() == SettingType.INTEGER || setting.type() == SettingType.DECIMAL) {
			return (back ? "Already at the minimum (" : "Already at the maximum (") + SettingText.format(setting, current) + ")";
		}
		return "No other value is allowed for " + setting.key();
	}

	/** Whether {@code next} (from {@link #next}) would change anything. */
	public static boolean changes(Object current, Object next) {
		return next != null && !Objects.equals(current, next);
	}
}
