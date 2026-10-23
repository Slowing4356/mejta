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
								dark("The server's settings: Configuration"))
						: List.of(gray("Framework settings: menu size, sounds,"), gray("logging and tooltips")))
				.onClick(ctx -> ctx.openScreen(new SettingsScreen(ctx.screen(), MejtaCore.MOD_ID, false)))
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
				.dynamicDescription(CoreMenus::configurationDescription)
				.onClick(ctx -> ctx.openScreen(new SettingsScreen(ctx.screen(), null, true)))
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
				.onClick(ctx -> openConfig(ctx, config))
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

	private static List<Component> configurationDescription() {
		List<Component> lines = new ArrayList<>();
		lines.add(gray("Every setting of MejtaCore and its addons,"));
		lines.add(gray("with search, categories and reset"));
		if (!RemoteConfigs.supported()) {
			lines.add(dark(MejtaCore.configs().all().size() + " configuration file(s)"));
		} else if (RemoteConfigs.incompatibleProtocol() != 0) {
			lines.add(Component.literal("Server: different MejtaCore version").withStyle(ChatFormatting.RED));
		} else if (RemoteConfigs.received() && !RemoteConfigs.permitted()) {
			lines.add(Component.literal("Server: needs mejtacore.config.edit").withStyle(ChatFormatting.RED));
		} else if (RemoteConfigs.received()) {
			lines.add(Component.literal("+ " + RemoteConfigs.configs().size() + " of the server's configs").withStyle(ChatFormatting.AQUA));
		}
		return lines;
	}

	/**
	 * Opens a config. While playing on a server that offers the same config (an addon installed on both sides),
	 * a click opens the SERVER's copy: editing the local file there changed only this computer and looked like
	 * the server ignored the change. Shift-click opens the local copy, which is then clearly labelled.
	 */
	private static void openConfig(MenuContext ctx, ConfigHandle local) {
		ctx.openScreen(new SettingsScreen(ctx.screen(), local.id(), !ctx.shiftDown()));
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
