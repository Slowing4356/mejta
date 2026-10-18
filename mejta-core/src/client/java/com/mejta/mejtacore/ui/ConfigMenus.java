package com.mejta.mejtacore.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.client.MenuContext;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;
import com.mejta.mejtacore.config.SettingText;

/** Builds editable menu pages for a {@link ConfigHandle}. */
public final class ConfigMenus {
	private ConfigMenus() {
	}

	/** One entry per setting, plus a "reset all" entry. */
	public static List<MenuEntry> settingsEntries(ConfigHandle handle) {
		List<MenuEntry> entries = new ArrayList<>();
		for (Setting<?> setting : handle.spec().settings()) {
			entries.add(settingEntry(handle, setting));
		}
		if (entries.isEmpty()) {
			entries.add(MenuEntry.builder("empty")
					.icon(() -> new ItemStack(Items.PAPER))
					.title(Component.literal("No settings").withStyle(ChatFormatting.GRAY))
					.build());
			return entries;
		}
		entries.add(MenuEntry.builder("reset_all")
				.icon(() -> new ItemStack(Items.TNT))
				.title(Component.literal("Reset All to Defaults").withStyle(ChatFormatting.RED))
				.description(
						Component.literal("Restores every setting in this file").withStyle(ChatFormatting.GRAY),
						Component.literal("Shift-click to confirm").withStyle(ChatFormatting.DARK_GRAY))
				.onClick(ctx -> {
					if (ctx.shiftDown()) {
						handle.resetAll();
						ctx.player().sendOverlayMessage(Component.literal("Settings reset to defaults").withStyle(ChatFormatting.GREEN));
						ctx.refresh();
					} else {
						ctx.player().sendOverlayMessage(Component.literal("Hold Shift and click to reset everything").withStyle(ChatFormatting.YELLOW));
					}
				})
				.build());
		return entries;
	}

	@SuppressWarnings("unchecked")
	private static <T> Setting<T> cast(Setting<?> setting) {
		return (Setting<T>) setting;
	}

	private static MenuEntry settingEntry(ConfigHandle handle, Setting<?> setting) {
		return MenuEntry.builder("setting_" + setting.key())
				.icon(() -> new ItemStack(iconFor(handle, setting)))
				.title(() -> Component.literal(setting.displayName() + ": ").withStyle(ChatFormatting.WHITE)
						.append(SettingText.isSensitive(setting)
								? Component.literal(SettingText.display(setting, handle.get(setting))).withStyle(ChatFormatting.AQUA)
								: valueText(setting, handle.get(setting))))
				.dynamicDescription(() -> describe(handle, setting))
				.onClick(ctx -> onClick(ctx, handle, setting))
				.build();
	}

	private static Item iconFor(ConfigHandle handle, Setting<?> setting) {
		return switch (setting.type()) {
			case BOOLEAN -> Boolean.TRUE.equals(handle.get(setting)) ? Items.LIME_DYE : Items.GRAY_DYE;
			case INTEGER -> Items.REPEATER;
			case DECIMAL -> Items.CLOCK;
			case STRING -> Items.NAME_TAG;
			case ENUM -> Items.HOPPER;
		};
	}

	private static MutableComponent valueText(Setting<?> setting, Object value) {
		return switch (setting.type()) {
			case BOOLEAN -> Boolean.TRUE.equals(value)
					? Component.literal("ON").withStyle(ChatFormatting.GREEN)
					: Component.literal("OFF").withStyle(ChatFormatting.RED);
			case STRING -> Component.literal("\"" + value + "\"").withStyle(ChatFormatting.AQUA);
			default -> Component.literal(format(value)).withStyle(ChatFormatting.AQUA);
		};
	}

	static String format(Object value) {
		if (value instanceof Double d) {
			BigDecimal decimal = BigDecimal.valueOf(d).stripTrailingZeros();
			return decimal.scale() <= 0 ? decimal.setScale(1).toPlainString() : decimal.toPlainString();
		}
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		return String.valueOf(value);
	}

	private static List<Component> describe(ConfigHandle handle, Setting<?> setting) {
		List<Component> lines = new ArrayList<>();
		if (setting.isExperimental()) {
			lines.add(Component.literal("Experimental").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		if (!setting.description().isEmpty()) {
			lines.add(Component.literal(setting.description()).withStyle(ChatFormatting.GRAY));
		}
		if (SettingText.isSensitive(setting)) {
			lines.add(Component.literal("Secret: the value is never shown").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			lines.add(Component.literal("Default: ").withStyle(ChatFormatting.DARK_GRAY)
					.append(valueText(setting, setting.defaultValue())));
		}
		if ((setting.type() == SettingType.INTEGER
				|| setting.type() == SettingType.DECIMAL) && hasRealRange(setting)) {
			lines.add(Component.literal("Range: " + format(setting.min()) + " to " + format(setting.max())).withStyle(ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.empty());
		switch (setting.type()) {
			case BOOLEAN -> lines.add(hint("Click to toggle"));
			case INTEGER -> {
				lines.add(hint("Left/Right-click: +1 / -1 (Shift: 10)"));
				lines.add(hint("Middle-click: type a value"));
			}
			case DECIMAL -> {
				lines.add(hint("Left/Right-click: +0.1 / -0.1 (Shift: 1)"));
				lines.add(hint("Middle-click: type a value"));
			}
			case ENUM -> lines.add(hint("Left/Right-click: next / previous"));
			case STRING -> lines.add(hint("Click to edit"));
		}
		lines.add(hint("Ctrl-click: reset to default"));
		return lines;
	}

	private static boolean hasRealRange(Setting<?> setting) {
		return switch (setting.type()) {
			case INTEGER -> setting.min().intValue() != Integer.MIN_VALUE || setting.max().intValue() != Integer.MAX_VALUE;
			case DECIMAL -> setting.min().doubleValue() != -Double.MAX_VALUE || setting.max().doubleValue() != Double.MAX_VALUE;
			default -> false;
		};
	}

	private static Component hint(String text) {
		return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
	}

	private static void onClick(MenuContext ctx, ConfigHandle handle, Setting<?> raw) {
		if (ctx.controlDown()) {
			handle.reset(raw);
			ctx.refresh();
			return;
		}
		boolean back = ctx.button() == 1;
		switch (raw.type()) {
			case BOOLEAN -> {
				Setting<Boolean> s = cast(raw);
				handle.trySet(s, !handle.get(s));
			}
			case INTEGER -> {
				Setting<Integer> s = cast(raw);
				if (ctx.button() == 2) {
					editAsText(ctx, handle, s, text -> Integer.parseInt(text.trim()));
					return;
				}
				long step = ctx.shiftDown() ? 10 : 1;
				long next = (long) handle.get(s) + (back ? -step : step);
				next = Math.clamp(next, s.min().longValue(), s.max().longValue());
				handle.trySet(s, (int) next);
			}
			case DECIMAL -> {
				Setting<Double> s = cast(raw);
				if (ctx.button() == 2) {
					editAsText(ctx, handle, s, text -> Double.parseDouble(text.trim()));
					return;
				}
				double step = ctx.shiftDown() ? 1.0 : 0.1;
				double next = handle.get(s) + (back ? -step : step);
				next = Math.round(next * 10000.0) / 10000.0;
				next = Math.clamp(next, s.min().doubleValue(), s.max().doubleValue());
				handle.trySet(s, next);
			}
			case STRING -> {
				Setting<String> s = cast(raw);
				editAsText(ctx, handle, s, text -> text);
				return;
			}
			case ENUM -> {
				if (raw.valueClass() == String.class) {
					cycleChoice(handle, cast(raw), back ? -1 : 1);
				} else {
					cycleEnum(handle, raw, back ? -1 : 1);
				}
			}
		}
		ctx.refresh();
	}

	private static <E extends Enum<E>> void cycleEnum(ConfigHandle handle, Setting<?> raw, int direction) {
		Setting<E> s = cast(raw);
		E[] values = s.valueClass().getEnumConstants();
		int index = handle.get(s).ordinal();
		for (int i = 1; i <= values.length; i++) {
			E candidate = values[Math.floorMod(index + direction * i, values.length)];
			if (handle.trySet(s, candidate)) {
				return;
			}
		}
	}

	private static void cycleChoice(ConfigHandle handle, Setting<String> s, int direction) {
		List<String> options = s.options();
		int index = Math.max(0, options.indexOf(handle.get(s)));
		for (int i = 1; i <= options.size(); i++) {
			if (handle.trySet(s, options.get(Math.floorMod(index + direction * i, options.size())))) {
				return;
			}
		}
	}

	private interface Parser<T> {
		T parse(String text);
	}

	private static <T> void editAsText(MenuContext ctx, ConfigHandle handle, Setting<T> setting, Parser<T> parser) {
		boolean sensitive = SettingText.isSensitive(setting);
		Component hint = sensitive
				? Component.literal("Secret: type a new value; the current one is not shown")
				: Component.literal("Default: " + format(setting.defaultValue())
						+ (hasRealRange(setting) ? "  (" + format(setting.min()) + " to " + format(setting.max()) + ")" : ""));
		ctx.openScreen(new TextInputScreen(ctx.screen(), Component.literal("Edit " + setting.displayName()),
				sensitive ? "" : format(handle.get(setting)), hint,
				text -> setting.isValid(parser.parse(text)),
				text -> handle.trySet(setting, parser.parse(text))));
	}
}
