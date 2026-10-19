package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.addons.ClientAddonExtensions;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.client.MenuContext;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.config.ConfigNames;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.network.RemoteConfigs;

/** The built-in MejtaCore menus. */
public final class CoreMenus {
	private CoreMenus() {
	}

	public static Screen mainMenu() {
		return new ChestMenuScreen(Component.literal("MejtaCore"), CoreMenus::mainEntries, null);
	}

	private static List<MenuEntry> mainEntries() {
		List<MenuEntry> entries = new ArrayList<>();
		entries.add(null);
		entries.add(MenuEntry.builder("core_settings")
				.icon(() -> new ItemStack(Items.COMPARATOR))
				.title(Component.literal("MejtaCore Settings").withStyle(ChatFormatting.GOLD))
				.dynamicDescription(() -> RemoteConfigs.supported()
						? List.of(gray("This computer's framework settings: menu"), gray("size, sounds, logging and tooltips"),
								dark("The server's: Configuration > Server Settings"))
						: List.of(gray("Framework settings: menu size, sounds,"), gray("logging and tooltips")))
				.onClick(ctx -> ctx.openPage(Component.literal("MejtaCore Settings"),
						() -> withNoteWhenRemote(ConfigMenus.settingsEntries(MejtaCore.coreConfig()))))
				.build());
		entries.add(null);
		entries.add(MenuEntry.builder("installed_addons")
				.icon(() -> new ItemStack(Items.CHEST))
				.title(Component.literal("Installed Addons").withStyle(ChatFormatting.GOLD))
				.dynamicDescription(() -> List.of(gray(addonSummary())))
				.onClick(ctx -> ctx.openPage(Component.literal("Installed Addons"), CoreMenus::addonEntries))
				.build());
		entries.add(null);
		entries.add(MenuEntry.builder("configuration")
				.icon(() -> new ItemStack(Items.BOOK))
				.title(Component.literal("Configuration").withStyle(ChatFormatting.GOLD))
				.dynamicDescription(() -> RemoteConfigs.supported()
						? List.of(gray(MejtaCore.configs().all().size() + " configuration file(s) on this computer"),
								Component.literal("+ Server Settings").withStyle(ChatFormatting.AQUA),
								gray("View, edit and reload settings"))
						: List.of(gray(MejtaCore.configs().all().size() + " configuration file(s)"),
								gray("View, edit and reload settings")))
				.onClick(ctx -> ctx.openPage(Component.literal("Configuration"), CoreMenus::configEntries))
				.build());
		entries.add(null);
		entries.add(MenuEntry.builder("about")
				.icon(() -> new ItemStack(Items.PAPER))
				.title(Component.literal("About").withStyle(ChatFormatting.GOLD))
				.description(gray("MejtaCore " + MejtaCore.version()), gray("Version and framework information"))
				.onClick(ctx -> ctx.openPage(Component.literal("About MejtaCore"), CoreMenus::aboutEntries))
				.build());
		entries.add(null);

		List<MenuEntry> addonEntries = ClientAddonExtensions.allMenuEntries();
		if (!addonEntries.isEmpty()) {
			for (int i = 0; i < 9; i++) {
				entries.add(null);
			}
			entries.addAll(addonEntries);
		}
		return entries;
	}

	private static String addonSummary() {
		Collection<AddonInfo> addons = MejtaCoreAPI.getAddons();
		long active = addons.stream().filter(AddonInfo::isActive).count();
		long failed = addons.size() - active;
		return active + " active" + (failed > 0 ? ", " + failed + " failed" : "");
	}

	private static List<MenuEntry> addonEntries() {
		List<MenuEntry> entries = new ArrayList<>();
		for (AddonInfo addon : MejtaCoreAPI.getAddons()) {
			entries.add(MenuEntry.builder("addon_" + addon.id())
					.icon(() -> MenuItems.iconFor(addon.iconItem()))
					.title(Component.literal(addon.name()).withStyle(addon.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED))
					.dynamicDescription(() -> addonDescription(addon))
					.onClick(ctx -> ctx.openPage(Component.literal(addon.name()), () -> addonDetail(addon)))
					.build());
		}
		if (entries.isEmpty()) {
			entries.add(MenuEntry.builder("no_addons")
					.icon(() -> new ItemStack(Items.PAPER))
					.title(Component.literal("No addons installed").withStyle(ChatFormatting.GRAY))
					.description(gray("Mejta addons appear here automatically"), gray("once they are installed."))
					.build());
		}
		List<Diagnostics.Entry> problems = Diagnostics.entries();
		if (!problems.isEmpty()) {
			entries.add(MenuEntry.builder("diagnostics")
					.icon(() -> new ItemStack(Items.RED_DYE))
					.title(Component.literal("Problems (" + problems.size() + ")").withStyle(ChatFormatting.RED))
					.description(gray("Rejected registrations, config errors"), gray("and failed addons"))
					.onClick(ctx -> ctx.openPage(Component.literal("Problems"), CoreMenus::diagnosticEntries))
					.build());
		}
		return entries;
	}

	private static List<Component> addonDescription(AddonInfo addon) {
		List<Component> lines = new ArrayList<>();
		lines.add(gray("Version " + addon.version()));
		if (!addon.description().isEmpty()) {
			lines.add(Component.literal(addon.description()).withStyle(ChatFormatting.WHITE));
		}
		lines.add(addon.isActive()
				? Component.literal("Active").withStyle(ChatFormatting.GREEN)
				: Component.literal("Failed: " + addon.failureReason().orElse("unknown")).withStyle(ChatFormatting.RED));
		if (!addon.definition().dependencies().isEmpty()) {
			lines.add(gray("Requires: " + String.join(", ", addon.definition().dependencies())));
		}
		if (!addon.definition().optionalDependencies().isEmpty()) {
			lines.add(gray("Optional: " + String.join(", ", addon.definition().optionalDependencies())));
		}
		if (MejtaCore.coreConfig().get(CoreSettings.SHOW_ADDON_IDS)) {
			lines.add(dark("ID: " + addon.id()));
			lines.add(dark("Source: " + addon.source()));
		}
		return lines;
	}

	private static List<MenuEntry> addonDetail(AddonInfo addon) {
		List<MenuEntry> entries = new ArrayList<>();
		entries.add(MenuEntry.builder("info")
				.icon(() -> MenuItems.iconFor(addon.iconItem()))
				.title(Component.literal(addon.name()).withStyle(addon.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED))
				.dynamicDescription(() -> addonDescription(addon))
				.build());
		addon.config().ifPresent(config -> entries.add(MenuEntry.builder("settings")
				.icon(() -> new ItemStack(Items.COMPARATOR))
				.title(Component.literal("Settings").withStyle(ChatFormatting.GOLD))
				.dynamicDescription(() -> {
					List<Component> lines = new ArrayList<>();
					lines.add(gray(config.spec().settings().size() + " setting(s) in " + config.file().getFileName()));
					lines.addAll(remoteNote(config));
					return lines;
				})
				.onClick(ctx -> openConfig(ctx, config, addon.name()))
				.build()));
		entries.addAll(ClientAddonExtensions.menuEntries(addon.id()));
		return entries;
	}

	private static List<MenuEntry> diagnosticEntries() {
		List<MenuEntry> entries = new ArrayList<>();
		int index = 0;
		for (Diagnostics.Entry problem : Diagnostics.entries()) {
			boolean error = problem.level() == Diagnostics.Level.ERROR;
			entries.add(MenuEntry.builder("problem_" + index++)
					.icon(() -> new ItemStack(error ? Items.RED_DYE : Items.YELLOW_DYE))
					.title(Component.literal((error ? "Error: " : "Warning: ") + problem.subject())
							.withStyle(error ? ChatFormatting.RED : ChatFormatting.YELLOW))
					.description(wrap(problem.message()))
					.build());
		}
		return entries;
	}

	private static List<Component> serverSettingsDescription() {
		if (RemoteConfigs.incompatibleProtocol() != 0) {
			return List.of(gray("Settings of the server you are playing on"),
					Component.literal("Different MejtaCore version on the server").withStyle(ChatFormatting.RED));
		}
		if (!RemoteConfigs.received()) {
			return List.of(gray("Settings of the server you are playing on"), dark("Click to load"));
		}
		if (!RemoteConfigs.permitted()) {
			return List.of(gray("Settings of the server you are playing on"),
					Component.literal("You need the permission mejtacore.config.edit").withStyle(ChatFormatting.RED));
		}
		return List.of(gray("Settings of the server you are playing on"),
				gray(RemoteConfigs.configs().size() + " configuration file(s)"),
				dark("Changes are checked and saved by the server"));
	}

	private static List<MenuEntry> serverConfigEntries() {
		List<MenuEntry> entries = new ArrayList<>();
		if (RemoteConfigs.incompatibleProtocol() != 0) {
			entries.add(MenuEntry.builder("server_incompatible")
					.icon(() -> new ItemStack(Items.BARRIER))
					.title(Component.literal("Different MejtaCore version").withStyle(ChatFormatting.RED))
					.description(gray("The server uses settings protocol " + RemoteConfigs.incompatibleProtocol() + ","),
							gray("this client " + com.mejta.mejtacore.network.ConfigPayloads.PROTOCOL + ". Update MejtaCore on both,"),
							gray("or use /mejtaadmin config menu"))
					.build());
			return entries;
		}
		if (!RemoteConfigs.received()) {
			entries.add(MenuEntry.builder("server_loading")
					.icon(() -> new ItemStack(Items.CLOCK))
					.title(Component.literal("Loading from the server...").withStyle(ChatFormatting.GRAY))
					.build());
			return entries;
		}
		if (!RemoteConfigs.permitted()) {
			entries.add(MenuEntry.builder("server_no_permission")
					.icon(() -> new ItemStack(Items.BARRIER))
					.title(Component.literal("No permission").withStyle(ChatFormatting.RED))
					.description(gray("Editing server settings needs the permission"), gray("mejtacore.config.edit (operator level 3 by default)"))
					.build());
			return entries;
		}
		for (ConfigHandle config : RemoteConfigs.configs()) {
			String name = RemoteConfigs.name(config);
			entries.add(MenuEntry.builder("server_config_" + config.id())
					.icon(() -> new ItemStack(Items.ENCHANTED_BOOK))
					.title(Component.literal(name).withStyle(ChatFormatting.AQUA))
					.dynamicDescription(() -> List.of(
							gray("On the server: config/mejta/" + config.id() + ".json"),
							gray(config.spec().settings().size() + " setting(s)"),
							dark("Click to edit")))
					.onClick(ctx -> openServerConfig(ctx, config.id(), name))
					.build());
		}
		return entries;
	}

	/**
	 * The server's copy of a config. The page looks the handle up by ID on every refresh: after the permission
	 * was revoked and granted again the catalog holds a new handle, and a page bound to the old one showed
	 * stale values forever.
	 */
	private static void openServerConfig(MenuContext ctx, String id, String name) {
		RemoteConfigs.request();
		ctx.openPage(Component.literal(name + " (Server)"), () -> RemoteConfigs.find(id)
				.map(ConfigMenus::settingsEntries)
				.orElseGet(() -> List.of(MenuEntry.builder("server_config_gone")
						.icon(() -> new ItemStack(Items.BARRIER))
						.title(Component.literal("Not available").withStyle(ChatFormatting.RED))
						.description(gray("The server no longer offers this config to you"))
						.build())));
	}

	/**
	 * Opens a config file of this computer. While playing on a server that offers the same config in Server
	 * Settings (an addon installed on both sides), a click opens the SERVER's copy: editing the local file there
	 * changed only this computer and looked like the server ignored the change. Shift-click opens the local copy,
	 * which is then clearly labelled.
	 */
	private static void openConfig(MenuContext ctx, ConfigHandle local, String name) {
		if (!RemoteConfigs.supported()) {
			ctx.openPage(Component.literal(name + " Settings"), () -> ConfigMenus.settingsEntries(local));
			return;
		}
		ConfigHandle server = RemoteConfigs.find(local.id()).orElse(null);
		if (server != null && !ctx.shiftDown()) {
			openServerConfig(ctx, local.id(), RemoteConfigs.name(server));
			ChestMenuScreen.showStatus(Component.literal("The SERVER's settings (Shift-click for this computer's copy)")
					.withStyle(ChatFormatting.AQUA));
			return;
		}
		ctx.openPage(Component.literal(name + " (This Computer)"), () -> withNoteWhenRemote(ConfigMenus.settingsEntries(local)));
		ChestMenuScreen.showStatus(Component.literal("This computer's copy: changes here do not reach the server")
				.withStyle(ChatFormatting.YELLOW));
	}

	/** Puts {@link #thisComputerNote()} first while connected to a server. */
	private static List<MenuEntry> withNoteWhenRemote(List<MenuEntry> entries) {
		if (!RemoteConfigs.supported()) {
			return entries;
		}
		List<MenuEntry> out = new ArrayList<>(entries.size() + 1);
		out.add(thisComputerNote());
		out.addAll(entries);
		return out;
	}

	private static MenuEntry thisComputerNote() {
		return MenuEntry.builder("this_computer_note")
				.icon(() -> new ItemStack(Items.PAPER))
				.title(Component.literal("This computer's settings").withStyle(ChatFormatting.YELLOW))
				.description(gray("These files are on your computer."),
						Component.literal("Changing them does NOT change the server.").withStyle(ChatFormatting.RED),
						gray("The server's settings are under"),
						gray("Configuration > Server Settings."),
						dark("Click to open Server Settings"))
				.onClick(CoreMenus::openServerSettings)
				.build();
	}

	/** Description lines of a local config while connected to a server. */
	private static List<Component> remoteNote(ConfigHandle local) {
		if (!RemoteConfigs.supported()) {
			return List.of();
		}
		if (RemoteConfigs.find(local.id()).isPresent()) {
			return List.of(Component.literal("Click: the SERVER's copy").withStyle(ChatFormatting.AQUA),
					dark("Shift-click: this computer's copy"));
		}
		return List.of(Component.literal("This computer only: does not change the server").withStyle(ChatFormatting.YELLOW));
	}

	private static void openServerSettings(MenuContext ctx) {
		RemoteConfigs.request();
		ctx.openPage(Component.literal("Server Settings"), CoreMenus::serverConfigEntries);
	}

	private static List<MenuEntry> configEntries() {
		List<MenuEntry> entries = new ArrayList<>();
		if (RemoteConfigs.supported()) {
			entries.add(MenuEntry.builder("server_settings")
					.icon(() -> new ItemStack(Items.COMMAND_BLOCK))
					.title(Component.literal("Server Settings").withStyle(ChatFormatting.GOLD))
					.dynamicDescription(CoreMenus::serverSettingsDescription)
					.onClick(CoreMenus::openServerSettings)
					.build());
			entries.add(thisComputerNote());
		}
		for (ConfigHandle config : MejtaCore.configs().all()) {
			String name = ConfigNames.displayName(config);
			entries.add(MenuEntry.builder("config_" + config.id())
					.icon(() -> new ItemStack(Items.BOOK))
					.title(() -> Component.literal(RemoteConfigs.supported() ? name + " (this computer)" : name)
							.withStyle(ChatFormatting.AQUA))
					.dynamicDescription(() -> {
						List<Component> lines = new ArrayList<>();
						lines.add(gray((RemoteConfigs.supported() ? "On this computer: " : "File: ") + "config/mejta/"
								+ config.file().getFileName()));
						lines.add(gray(config.spec().settings().size() + " setting(s), format v" + config.spec().version()));
						lines.add(config.isDirty() ? Component.literal("Unsaved changes").withStyle(ChatFormatting.YELLOW) : dark("Saved"));
						List<Component> note = remoteNote(config);
						lines.addAll(note.isEmpty() ? List.of(dark("Click to edit")) : note);
						return lines;
					})
					.onClick(ctx -> openConfig(ctx, config, name))
					.build());
		}
		entries.add(MenuEntry.builder("reload")
				.icon(() -> new ItemStack(Items.HOPPER))
				.title(Component.literal("Reload from Disk").withStyle(ChatFormatting.YELLOW))
				.dynamicDescription(() -> RemoteConfigs.supported()
						? List.of(gray("Re-reads this computer's files in"), gray("config/mejta, discarding unsaved changes"),
								dark("(not the server's: /mejtaadmin config reload)"))
						: List.of(gray("Re-reads all files in config/mejta,"), gray("discarding unsaved changes")))
				.onClick(ctx -> {
					int count = MejtaCore.configs().reloadAll();
					ChestMenuScreen.showStatus(Component.literal("Reloaded " + count + " configuration file(s)"
							+ (RemoteConfigs.supported() ? " on this computer" : "")).withStyle(ChatFormatting.GREEN));
					ctx.refresh();
				})
				.build());
		entries.add(MenuEntry.builder("save")
				.icon(() -> new ItemStack(Items.WRITABLE_BOOK))
				.title(Component.literal("Save Now").withStyle(ChatFormatting.YELLOW))
				.description(gray("Changes are also saved automatically"), gray("when the menu closes"))
				.onClick(ctx -> {
					MejtaCore.configs().saveAll();
					ChestMenuScreen.showStatus(Component.literal("Configuration saved"
							+ (RemoteConfigs.supported() ? " on this computer (server changes are saved by the server)" : ""))
							.withStyle(ChatFormatting.GREEN));
					ctx.refresh();
				})
				.build());
		return entries;
	}

	private static List<MenuEntry> aboutEntries() {
		FabricLoader loader = FabricLoader.getInstance();
		List<MenuEntry> entries = new ArrayList<>();
		entries.add(info("version", Items.NETHER_STAR, "MejtaCore " + MejtaCore.version(),
				gray("Core framework for Mejta addon mods"), gray("Public API version " + MejtaCoreAPI.API_VERSION)));
		entries.add(info("minecraft", Items.GRASS_BLOCK, "Minecraft " + modVersion(loader, "minecraft")));
		entries.add(info("loader", Items.CRAFTING_TABLE, "Fabric Loader " + modVersion(loader, "fabricloader")));
		entries.add(info("fabric_api", Items.ANVIL, "Fabric API " + modVersion(loader, "fabric-api")));
		entries.add(info("addons", Items.CHEST, "Addons", gray(addonSummary())));
		entries.add(info("config_dir", Items.BOOKSHELF, "Configuration Folder", gray("config/mejta/")));
		entries.add(info("commands", Items.COMMAND_BLOCK, "Commands",
				gray("/mejtacore (or /mejta) opens this menu"),
				gray("/mejtacore help lists all commands")));
		return entries;
	}

	private static String modVersion(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}

	private static MenuEntry info(String id, net.minecraft.world.item.Item icon, String title, Component... lines) {
		return MenuEntry.builder(id)
				.icon(() -> new ItemStack(icon))
				.title(Component.literal(title).withStyle(ChatFormatting.AQUA))
				.description(lines)
				.build();
	}

	private static List<Component> wrap(String text) {
		List<Component> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() + word.length() > 48 && !line.isEmpty()) {
				lines.add(gray(line.toString()));
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
		}
		if (!line.isEmpty()) {
			lines.add(gray(line.toString()));
		}
		return lines;
	}

	private static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent dark(String text) {
		return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
	}
}
