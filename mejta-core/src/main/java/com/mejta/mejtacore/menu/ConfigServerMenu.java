package com.mejta.mejtacore.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;
import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.ConfigNames;
import com.mejta.mejtacore.config.SettingGroups;
import com.mejta.mejtacore.config.SettingSteps;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.network.ServerConfigSync;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/**
 * {@code /mejtaadmin config menu}: the server's settings as a chest menu built with {@link ServerMenu} (always a chest, also for
 * clients with MejtaCore, which get the settings editor from {@code /mejtaadmin config} instead), so
 * it works for players without any mod. Nothing in it can be moved.
 *
 * <p>Every change goes through {@link ServerConfigSync#applyAsPlayer} like the client's Server Settings:
 * permission check, validation with the addon's rules, save, audit entry and notification. The menu
 * re-checks the permission on every click and refresh ({@code validWhile}) and redraws when values change.
 * Secret settings are shown as {@code (set)}/{@code (not set)} and cannot be changed here.
 *
 * <p>A config with many settings opens on its groups first: settings are grouped by the part of their
 * key before the first dot ({@code homes.max} is in "Homes"), keys without a dot are in "General".
 */
public final class ConfigServerMenu {
	static final int ROWS = 6;
	static final int PER_PAGE = 45;
	static final int SLOT_BACK = 45;
	static final int SLOT_PREVIOUS = 48;
	static final int SLOT_INFO = 49;
	static final int SLOT_NEXT = 50;
	static final int SLOT_CLOSE = 53;
	/** Longest value shown in an icon; longer text is cut (it is still editable by command). */
	private static final int VALUE_LIMIT = 40;
	private static final int LORE_WIDTH = 40;
	private static final String SET_COMMAND = "/mejtaadmin config set ";

	/** Feedback of the last change, shown in the info icon while the player moves between pages. */
	private static final class Session {
		Component status = Component.literal("No changes yet").withStyle(ChatFormatting.DARK_GRAY);
	}

	private ConfigServerMenu() {
	}

	/** Opens the list of configs {@code player} may edit. */
	public static boolean open(ServerPlayer player) {
		return openList(player, new Session(), 0);
	}

	/** Opens one config's settings, or explains in chat why not. */
	public static boolean open(ServerPlayer player, String configId) {
		if (ServerConfigSync.editable(player.permissions(), configId).isEmpty()) {
			player.sendSystemMessage(Component.literal("You may not edit config '" + configId + "'").withStyle(ChatFormatting.RED));
			return false;
		}
		return openConfig(player, new Session(), configId, 0);
	}

	// ---------------------------------------------------------------- list of configs

	private static boolean openList(ServerPlayer player, Session session, int page) {
		List<ConfigHandle> configs = ServerConfigSync.editableBy(player.permissions());
		int pages = pageCount(configs.size());
		int current = Math.clamp(page, 0, pages - 1);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Server Settings"), ROWS);
		int start = current * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < configs.size(); i++) {
			ConfigHandle handle = configs.get(start + i);
			String id = handle.id();
			menu.button(i, () -> configIcon(handle), click -> openConfig(click.player(), session, id, 0));
		}
		if (configs.isEmpty()) {
			menu.icon(22, () -> MenuIcons.of(Items.BARRIER, Component.literal("Nothing to edit").withStyle(ChatFormatting.RED)));
		}
		navigation(menu, session, current, pages,
				p -> openList(p, session, current - 1), p -> openList(p, session, current + 1), null);
		return ButtonMenu.open(player, menu.validWhile(p -> MejtaCoreAPI.hasPermission(p, PermissionRegistry.CONFIG_EDIT))
				.refreshEvery(20)
				.fillEmpty()
				.build());
	}

	private static ItemStack configIcon(ConfigHandle handle) {
		long secrets = handle.spec().settings().stream().filter(SettingText::isSensitive).count();
		List<Component> lore = new ArrayList<>();
		lore.add(MenuIcons.line("On the server: config/mejta/" + handle.id() + ".json"));
		lore.add(MenuIcons.line(handle.spec().settings().size() + " setting(s)"
				+ (secrets > 0 ? ", " + secrets + " secret" : "")));
		lore.add(Component.empty());
		lore.add(hint("Click to edit"));
		return MenuIcons.of(Items.ENCHANTED_BOOK, Component.literal(ConfigNames.displayName(handle)).withStyle(ChatFormatting.AQUA), lore);
	}

	// ---------------------------------------------------------------- one config

	private static boolean openConfig(ServerPlayer player, Session session, String configId, int page) {
		ConfigHandle handle = ServerConfigSync.editable(player.permissions(), configId).orElse(null);
		if (handle == null) {
			session.status = Component.literal("You may not edit config '" + configId + "'").withStyle(ChatFormatting.RED);
			player.sendSystemMessage(session.status);
			return false;
		}
		Map<String, List<Setting<?>>> groups = SettingGroups.of(handle.spec().settings());
		if (SettingGroups.grouped(handle.spec().settings().size(), groups.size())) {
			return openGroups(player, session, handle, page, groups);
		}
		return openSettings(player, session, handle, null, handle.spec().settings(), page);
	}

	/** The settings of one config, or of one group of it ({@code group} not null). */
	private static boolean openSettings(ServerPlayer player, Session session, ConfigHandle handle, String group,
			List<Setting<?>> settings, int page) {
		String configId = handle.id();
		int pages = pageCount(settings.size());
		int current = Math.clamp(page, 0, pages - 1);
		String name = ConfigNames.displayName(handle);
		String title = group == null ? shorten(name, 24) + " settings" : shorten(name, 16) + ": " + shorten(SettingGroups.displayName(group), 18);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal(title), ROWS);
		int start = current * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < settings.size(); i++) {
			Setting<?> setting = settings.get(start + i);
			menu.button(i, () -> settingIcon(handle, setting), click -> onSettingClick(click, session, configId, setting));
		}
		if (settings.isEmpty()) {
			menu.icon(22, () -> MenuIcons.of(Items.PAPER, Component.literal("No settings").withStyle(ChatFormatting.GRAY)));
		}
		navigation(menu, session, current, pages,
				p -> reopenSettings(p, session, configId, group, current - 1), p -> reopenSettings(p, session, configId, group, current + 1),
				group == null ? p -> openList(p, session, 0) : p -> openConfig(p, session, configId, 0),
				group == null ? "All configs" : "All groups of " + shorten(name, 24));
		// Re-checked on every click and refresh: a revoked permission (or a lost permissions-file grant) closes it.
		return ButtonMenu.open(player, menu.validWhile(p -> ServerConfigSync.editable(p.permissions(), configId).isPresent())
				.refreshEvery(10)
				.fillEmpty()
				.build());
	}

	private static boolean reopenSettings(ServerPlayer player, Session session, String configId, String group, int page) {
		ConfigHandle handle = ServerConfigSync.editable(player.permissions(), configId).orElse(null);
		if (handle == null) {
			return openConfig(player, session, configId, 0);
		}
		List<Setting<?>> settings = group == null ? handle.spec().settings() : SettingGroups.of(handle.spec().settings()).getOrDefault(group, List.of());
		return openSettings(player, session, handle, group, settings, page);
	}

	// ---------------------------------------------------------------- groups

	private static boolean openGroups(ServerPlayer player, Session session, ConfigHandle handle, int page,
			Map<String, List<Setting<?>>> groups) {
		String configId = handle.id();
		List<String> names = new ArrayList<>(groups.keySet());
		int pages = pageCount(names.size());
		int current = Math.clamp(page, 0, pages - 1);
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal(shorten(ConfigNames.displayName(handle), 24) + " settings"), ROWS);
		int start = current * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < names.size(); i++) {
			String group = names.get(start + i);
			menu.button(i, () -> groupIcon(handle, group), click -> reopenSettings(click.player(), session, configId, group, 0));
		}
		navigation(menu, session, current, pages,
				p -> openConfig(p, session, configId, current - 1), p -> openConfig(p, session, configId, current + 1),
				p -> openList(p, session, 0), "All configs");
		return ButtonMenu.open(player, menu.validWhile(p -> ServerConfigSync.editable(p.permissions(), configId).isPresent())
				.refreshEvery(20)
				.fillEmpty()
				.build());
	}

	private static ItemStack groupIcon(ConfigHandle handle, String group) {
		List<Setting<?>> settings = SettingGroups.of(handle.spec().settings()).getOrDefault(group, List.of());
		long changed = settings.stream().filter(s -> !SettingText.isSensitive(s) && !java.util.Objects.equals(handle.get(s), s.defaultValue())).count();
		List<Component> lore = new ArrayList<>();
		lore.add(MenuIcons.line(settings.size() + " setting(s)" + (changed > 0 ? ", " + changed + " changed" : "")));
		int shown = 0;
		for (Setting<?> setting : settings) {
			if (shown++ == 8) {
				lore.add(Component.literal("  ...").withStyle(ChatFormatting.DARK_GRAY));
				break;
			}
			lore.add(Component.literal("  " + setting.displayName()).withStyle(ChatFormatting.DARK_GRAY));
		}
		lore.add(Component.empty());
		lore.add(hint("Click to open"));
		return MenuIcons.of(SettingGroups.icon(group), Component.literal(SettingGroups.displayName(group)).withStyle(ChatFormatting.AQUA), lore);
	}

	static ItemStack settingIcon(ConfigHandle handle, Setting<?> setting) {
		Object value = handle.get(setting);
		boolean secret = SettingText.isSensitive(setting);
		MutableComponent name = Component.literal(setting.displayName() + ": ").withStyle(ChatFormatting.WHITE)
				.append(valueText(setting, value));
		return MenuIcons.of(iconFor(setting, value), name, describe(handle, setting, value, secret));
	}

	private static Item iconFor(Setting<?> setting, Object value) {
		if (SettingText.isSensitive(setting)) {
			return Items.TRIPWIRE_HOOK;
		}
		return switch (setting.type()) {
			case BOOLEAN -> Boolean.TRUE.equals(value) ? Items.LIME_DYE : Items.GRAY_DYE;
			case INTEGER -> Items.REPEATER;
			case DECIMAL -> Items.CLOCK;
			case STRING -> Items.NAME_TAG;
			case ENUM -> Items.HOPPER;
		};
	}

	private static MutableComponent valueText(Setting<?> setting, Object value) {
		if (SettingText.isSensitive(setting)) {
			return Component.literal(SettingText.display(setting, value)).withStyle(ChatFormatting.AQUA);
		}
		return switch (setting.type()) {
			case BOOLEAN -> Boolean.TRUE.equals(value)
					? Component.literal("ON").withStyle(ChatFormatting.GREEN)
					: Component.literal("OFF").withStyle(ChatFormatting.RED);
			case STRING -> Component.literal("\"" + shorten(String.valueOf(value), VALUE_LIMIT) + "\"").withStyle(ChatFormatting.AQUA);
			default -> Component.literal(shorten(SettingText.format(setting, value), VALUE_LIMIT)).withStyle(ChatFormatting.AQUA);
		};
	}

	private static List<Component> describe(ConfigHandle handle, Setting<?> setting, Object value, boolean secret) {
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Key: " + setting.key()).withStyle(ChatFormatting.DARK_GRAY));
		if (setting.isExperimental()) {
			lore.add(Component.literal("Experimental").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		for (String line : wrap(setting.description())) {
			lore.add(MenuIcons.line(line));
		}
		lore.add(Component.literal("Current: ").withStyle(ChatFormatting.GRAY).append(valueText(setting, value)));
		if (secret) {
			lore.add(Component.literal("Secret: the value is never shown").withStyle(ChatFormatting.DARK_GRAY));
			lore.add(Component.empty());
			lore.add(Component.literal("Not editable in this menu. From the").withStyle(ChatFormatting.YELLOW));
			lore.add(Component.literal("server console:").withStyle(ChatFormatting.YELLOW));
			lore.add(Component.literal("mejtaadmin config set " + handle.id() + " " + setting.key() + " <value>").withStyle(ChatFormatting.GRAY));
			return lore;
		}
		lore.add(Component.literal("Default: ").withStyle(ChatFormatting.GRAY).append(valueText(setting, setting.defaultValue())));
		if (SettingSteps.hasRealRange(setting)) {
			lore.add(MenuIcons.line("Range: " + SettingText.format(setting, setting.min()) + " to " + SettingText.format(setting, setting.max())));
		}
		if (setting.type() == SettingType.ENUM) {
			List<String> options = setting.valueClass() == String.class ? setting.options()
					: java.util.Arrays.stream(setting.valueClass().getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
			for (String line : wrap("Options: " + String.join(", ", options))) {
				lore.add(MenuIcons.line(line));
			}
		}
		lore.add(Component.empty());
		switch (setting.type()) {
			case BOOLEAN -> lore.add(hint("Click: toggle"));
			case ENUM -> lore.add(hint("Left-click: next, right-click: previous"));
			case INTEGER -> lore.add(hint("Left/right-click: +1 / -1 (Shift: 10)"));
			case DECIMAL -> {
				double raw = SettingSteps.decimalStep(setting);
				String step = SettingText.format(setting, raw);
				String big = SettingText.format(setting, java.math.BigDecimal.valueOf(raw)
						.multiply(java.math.BigDecimal.valueOf(SettingSteps.BIG_STEP)).doubleValue());
				lore.add(hint("Left/right-click: +" + step + " / -" + step));
				lore.add(hint("Shift-click: +" + big + " / -" + big));
			}
			case STRING -> lore.add(hint("Click: get a command to type the value"));
		}
		lore.add(hint("Drop key (Q): reset to default"));
		return lore;
	}

	private static void onSettingClick(MenuClick click, Session session, String configId, Setting<?> setting) {
		ServerPlayer player = click.player();
		ConfigHandle handle = ServerConfigSync.editable(player.permissions(), configId).orElse(null);
		if (handle == null) {
			click.menu().close();
			return;
		}
		if (SettingText.isSensitive(setting)) {
			feedback(player, session, new ConfigEditing.Result(false, setting.key()
					+ " is a secret setting: it cannot be changed from the menu. Set it from the server console with "
					+ SET_COMMAND.substring(1) + configId + " " + setting.key() + " <value>"));
			click.menu().refresh();
			return;
		}
		MenuClick.Type type = click.type();
		if (type == MenuClick.Type.DROP || type == MenuClick.Type.CTRL_DROP) {
			feedback(player, session, ServerConfigSync.applyAsPlayer(player, configId, ConfigEditing.Op.RESET.name(), setting.key(), ""));
			click.menu().refresh();
			return;
		}
		boolean left = type == MenuClick.Type.LEFT || type == MenuClick.Type.SHIFT_LEFT;
		if (!left && !type.isRight()) {
			return; // number keys, middle click, double click, drag: nothing to do
		}
		if (setting.type() == SettingType.STRING) {
			click.menu().close();
			suggestCommand(player, handle, setting);
			return;
		}
		Object current = handle.get(setting);
		Object next = SettingSteps.next(setting, current, type.isRight(), type.isShift());
		if (!SettingSteps.changes(current, next)) {
			feedback(player, session, new ConfigEditing.Result(false, SettingSteps.unchangedReason(setting, current, type.isRight())));
			click.menu().refresh();
			return;
		}
		feedback(player, session, ServerConfigSync.applyAsPlayer(player, configId, ConfigEditing.Op.SET.name(), setting.key(),
				SettingText.format(setting, next)));
		click.menu().refresh();
	}

	/** Text can't be typed into a chest: hand the player a ready command in chat instead. */
	private static void suggestCommand(ServerPlayer player, ConfigHandle handle, Setting<?> setting) {
		String command = SET_COMMAND + handle.id() + " " + setting.key() + " ";
		player.sendSystemMessage(Component.literal("[MejtaCore] ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("Click here to type a new value for " + setting.key())
						.withStyle(ChatFormatting.AQUA)
						.withStyle(ChatFormatting.UNDERLINE)
						.withStyle(style -> style
						.withClickEvent(new ClickEvent.SuggestCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))))));
		player.sendSystemMessage(Component.literal("Current: \"" + shorten(String.valueOf(handle.get(setting)), 200) + "\"")
				.withStyle(ChatFormatting.GRAY)
				.append(Component.literal("  (default \"" + shorten(String.valueOf(setting.defaultValue()), 100) + "\"; reopen with /mejtaadmin config menu "
						+ handle.id() + ")").withStyle(ChatFormatting.DARK_GRAY)));
	}

	/** Every result is shown: in the menu's info icon, and refusals in chat as well (they stay readable). */
	private static void feedback(ServerPlayer player, Session session, ConfigEditing.Result result) {
		ChatFormatting color = result.ok() ? ChatFormatting.GREEN : ChatFormatting.RED;
		Component message = Component.literal(result.message()).withStyle(color);
		session.status = Component.literal(shorten(result.message(), 60)).withStyle(color);
		player.sendOverlayMessage(message);
		if (!result.ok()) {
			player.sendSystemMessage(Component.literal("[MejtaCore] ").withStyle(ChatFormatting.DARK_GRAY).append(message));
		}
	}

	// ---------------------------------------------------------------- navigation row

	private interface Opener {
		void open(ServerPlayer player);
	}

	private static void navigation(ServerMenu.Builder menu, Session session, int page, int pages, Opener previous, Opener next,
			Opener back) {
		navigation(menu, session, page, pages, previous, next, back, "All configs");
	}

	private static void navigation(ServerMenu.Builder menu, Session session, int page, int pages, Opener previous, Opener next,
			Opener back, String backTo) {
		if (back != null) {
			menu.backButton(SLOT_BACK, backTo.toLowerCase(java.util.Locale.ROOT), back::open);
		}
		if (page > 0) {
			menu.button(SLOT_PREVIOUS, () -> MenuIcons.page(false, page, pages), click -> previous.open(click.player()));
		}
		if (page < pages - 1) {
			menu.button(SLOT_NEXT, () -> MenuIcons.page(true, page + 2, pages), click -> next.open(click.player()));
		}
		menu.icon(SLOT_INFO, () -> MenuIcons.of(Items.PAPER,
				Component.literal(pages > 1 ? "Page " + (page + 1) + " / " + pages : "Server Settings").withStyle(ChatFormatting.WHITE),
				List.of(Component.literal("Last change:").withStyle(ChatFormatting.GRAY), session.status,
						Component.empty(),
						Component.literal("Changes are checked, saved and").withStyle(ChatFormatting.DARK_GRAY),
						Component.literal("logged by the server at once.").withStyle(ChatFormatting.DARK_GRAY))));
		menu.closeButton(SLOT_CLOSE);
	}

	// ---------------------------------------------------------------- text helpers

	private static int pageCount(int entries) {
		return Math.max(1, (entries + PER_PAGE - 1) / PER_PAGE);
	}

	private static Component hint(String text) {
		return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
	}

	static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 3) + "...";
	}

	static List<String> wrap(String text) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!line.isEmpty() && line.length() + 1 + word.length() > LORE_WIDTH) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
			if (lines.size() >= 6) {
				break;
			}
		}
		if (!line.isEmpty() && lines.size() < 7) {
			lines.add(line.toString());
		}
		return lines;
	}
}
