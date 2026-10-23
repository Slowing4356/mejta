package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.addons.ClientAddonExtensions;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.network.RemoteConfigs;

/**
 * The MejtaCore menu ({@code /mejta}): Overview, one page per installed addon, Problems and About in a sidebar.
 * The overview leads to the settings editor and every addon; an addon's page shows what it is, whether it
 * runs, its settings and the menu entries it added.
 */
public class MainMenuScreen extends CardScreen {
	public static final String OVERVIEW = "overview";
	public static final String PROBLEMS = "problems";
	public static final String ABOUT = "about";
	private static final String ADDON = "addon:";

	private String section = OVERVIEW;

	public MainMenuScreen(@Nullable Screen parent) {
		super(Component.literal("MejtaCore"), parent);
	}

	// ---------------------------------------------------------------- sidebar

	@Override
	protected List<SidebarEntry> sidebarEntries() {
		List<SidebarEntry> entries = new ArrayList<>();
		entries.add(new SidebarEntry(OVERVIEW, Component.literal("Overview"), null));
		for (AddonInfo addon : MejtaCoreAPI.getAddons()) {
			entries.add(new SidebarEntry(ADDON + addon.id(), Component.literal(addon.name())
					.withStyle(addon.isActive() ? ChatFormatting.WHITE : ChatFormatting.RED),
					Component.literal(addon.name() + " " + addon.version() + (addon.isActive() ? "" : " (failed)"))));
		}
		int problems = Diagnostics.entries().size();
		entries.add(new SidebarEntry(PROBLEMS, problems == 0 ? Component.literal("Problems")
				: Component.literal("Problems (" + problems + ")").withStyle(ChatFormatting.RED), null));
		entries.add(new SidebarEntry(ABOUT, Component.literal("About"), null));
		return entries;
	}

	@Override
	protected String selectedSidebar() {
		return section;
	}

	@Override
	protected void sidebarClicked(String id) {
		show(id);
	}

	/** Shows a section: {@link #OVERVIEW}, {@link #PROBLEMS}, {@link #ABOUT} or {@code "addon:<id>"}. */
	public void show(String id) {
		section = id;
		refresh();
	}

	public String section() {
		return section;
	}

	// ---------------------------------------------------------------- content

	@Override
	protected List<Component> buildHeader() {
		if (section.startsWith(ADDON)) {
			AddonInfo addon = MejtaCoreAPI.getAddon(section.substring(ADDON.length())).orElse(null);
			return addon == null ? List.of() : addonHeader(addon);
		}
		return switch (section) {
			case PROBLEMS -> List.of(Component.literal("Problems").withStyle(ChatFormatting.BOLD),
					gray("Rejected registrations, config errors and failed addons"));
			case ABOUT -> List.of(Component.literal("About MejtaCore").withStyle(ChatFormatting.BOLD),
					gray("Core framework for Mejta addon mods"));
			default -> List.of(Component.literal("MejtaCore " + MejtaCore.version()).withStyle(ChatFormatting.BOLD),
					gray(addonSummary()), connectionLine());
		};
	}

	@Override
	protected List<Item> buildItems() {
		if (section.startsWith(ADDON)) {
			AddonInfo addon = MejtaCoreAPI.getAddon(section.substring(ADDON.length())).orElse(null);
			return addon == null ? List.of() : addonItems(addon);
		}
		return switch (section) {
			case PROBLEMS -> problemItems();
			case ABOUT -> aboutItems();
			default -> overviewItems();
		};
	}

	@Override
	protected Component emptyText() {
		return section.equals(PROBLEMS)
				? Component.literal("No problems: every addon loaded").withStyle(ChatFormatting.GREEN)
				: super.emptyText();
	}

	private List<Item> overviewItems() {
		List<Item> items = new ArrayList<>();
		items.add(new Heading(Component.literal("Settings")));
		items.add(new Card("settings", new ItemStack(Items.COMPARATOR), Component.literal("Settings").withStyle(ChatFormatting.GOLD),
				settingsLines(), click -> minecraft.setScreen(new SettingsScreen(this, null, true)), false));
		if (RemoteConfigs.supported()) {
			items.add(new Card("local_settings", new ItemStack(Items.REPEATER), Component.literal("This Computer").withStyle(ChatFormatting.GOLD),
					List.of(gray("Your own MejtaCore settings: sounds,"), gray("server menus as screens, tooltips")),
					click -> minecraft.setScreen(new SettingsScreen(this, MejtaCore.MOD_ID, false)), false));
		}
		boolean screens = MejtaCore.coreConfig().get(CoreSettings.SERVER_MENU_SCREENS);
		items.add(new Card("server_menu_style", new ItemStack(screens ? Items.ITEM_FRAME : Items.CHEST),
				Component.literal("Server Menus: " + (screens ? "Screens" : "Chests")).withStyle(ChatFormatting.GOLD),
				List.of(gray(screens ? "Homes, warps and other addon menus open" : "Addon menus open as chests,"),
						gray(screens ? "as screens like this one" : "like for players without MejtaCore"),
						dark("Click to switch")),
				click -> {
					MejtaCore.coreConfig().set(CoreSettings.SERVER_MENU_SCREENS, !screens);
					refresh();
				}, screens));

		Collection<AddonInfo> addons = MejtaCoreAPI.getAddons();
		if (!addons.isEmpty()) {
			items.add(new Heading(Component.literal("Addons")));
			for (AddonInfo addon : addons) {
				List<Component> lines = new ArrayList<>();
				lines.add(addon.isActive()
						? Component.literal("Version " + addon.version()).withStyle(ChatFormatting.GRAY)
						: Component.literal("Failed: " + addon.failureReason().orElse("unknown")).withStyle(ChatFormatting.RED));
				if (!addon.description().isEmpty()) {
					lines.add(Component.literal(addon.description()).withStyle(ChatFormatting.WHITE));
				}
				lines.add(dark("Click for its settings and actions"));
				items.add(new Card(ADDON + addon.id(), MenuItems.iconFor(addon.iconItem()),
						Component.literal(addon.name()).withStyle(addon.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED),
						lines, click -> show(ADDON + addon.id()), false));
			}
		}

		List<MenuEntry> entries = ClientAddonExtensions.allMenuEntries();
		if (!entries.isEmpty()) {
			items.add(new Heading(Component.literal("Addon Actions")));
			for (MenuEntry entry : entries) {
				items.add(entryCard(entry));
			}
		}
		return items;
	}

	private List<Component> settingsLines() {
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

	private static Component connectionLine() {
		if (!RemoteConfigs.supported()) {
			return dark("Settings here are this computer's (config/mejta/)");
		}
		if (RemoteConfigs.incompatibleProtocol() != 0) {
			return Component.literal("The server runs a different MejtaCore version: its settings cannot be edited here")
					.withStyle(ChatFormatting.RED);
		}
		if (RemoteConfigs.received() && !RemoteConfigs.permitted()) {
			return dark("Server settings need the permission mejtacore.config.edit");
		}
		return Component.literal("Connected to a server with MejtaCore: its settings are editable here").withStyle(ChatFormatting.AQUA);
	}

	// ---------------------------------------------------------------- an addon's page

	private List<Component> addonHeader(AddonInfo addon) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(addon.name()).withStyle(ChatFormatting.BOLD)
				.append(Component.literal("  " + addon.version()).withStyle(ChatFormatting.GRAY)));
		lines.add(addon.isActive()
				? Component.literal("Active").withStyle(ChatFormatting.GREEN)
				: Component.literal("Failed: " + addon.failureReason().orElse("unknown")).withStyle(ChatFormatting.RED));
		if (!addon.description().isEmpty()) {
			lines.add(Component.literal(addon.description()).withStyle(ChatFormatting.WHITE));
		}
		if (!addon.definition().dependencies().isEmpty()) {
			lines.add(gray("Requires: " + String.join(", ", addon.definition().dependencies())));
		}
		if (!addon.definition().optionalDependencies().isEmpty()) {
			lines.add(gray("Optional: " + String.join(", ", addon.definition().optionalDependencies())));
		}
		if (MejtaCore.coreConfig().get(CoreSettings.SHOW_ADDON_IDS)) {
			lines.add(dark("ID: " + addon.id() + ", from " + addon.source()));
		}
		return lines;
	}

	private List<Item> addonItems(AddonInfo addon) {
		List<Item> items = new ArrayList<>();
		ConfigHandle config = addon.config().orElse(null);
		if (config != null) {
			boolean remote = RemoteConfigs.find(config.id()).isPresent();
			List<Component> lines = new ArrayList<>();
			lines.add(gray(config.spec().settings().size() + " setting(s) in " + config.file().getFileName()));
			if (remote) {
				lines.add(Component.literal("The SERVER's copy").withStyle(ChatFormatting.AQUA));
			} else if (RemoteConfigs.supported()) {
				lines.add(Component.literal("This computer only: does not change the server").withStyle(ChatFormatting.YELLOW));
			}
			items.add(new Card("settings", new ItemStack(Items.COMPARATOR), Component.literal("Settings").withStyle(ChatFormatting.GOLD),
					lines, click -> minecraft.setScreen(new SettingsScreen(this, config.id(), true)), false));
			if (remote) {
				items.add(new Card("local_settings", new ItemStack(Items.REPEATER),
						Component.literal("This Computer's Copy").withStyle(ChatFormatting.GOLD),
						List.of(gray("The file in config/mejta/ on this computer;"), gray("changing it does not change the server")),
						click -> minecraft.setScreen(new SettingsScreen(this, config.id(), false)), false));
			}
		}
		List<MenuEntry> entries = ClientAddonExtensions.menuEntries(addon.id());
		if (!entries.isEmpty()) {
			items.add(new Heading(Component.literal("Actions")));
			for (MenuEntry entry : entries) {
				items.add(entryCard(entry));
			}
		}
		if (items.isEmpty()) {
			items.add(new Card("nothing", new ItemStack(Items.PAPER), Component.literal("Nothing to set up here").withStyle(ChatFormatting.GRAY),
					List.of(gray(addon.isActive() ? "This addon has no settings on this computer" : "This addon did not start")), null, false));
		}
		return items;
	}

	// ---------------------------------------------------------------- problems and about

	private static List<Item> problemItems() {
		List<Item> items = new ArrayList<>();
		int index = 0;
		for (Diagnostics.Entry problem : Diagnostics.entries()) {
			boolean error = problem.level() == Diagnostics.Level.ERROR;
			items.add(new Card("problem_" + index++, new ItemStack(error ? Items.RED_DYE : Items.YELLOW_DYE),
					Component.literal((error ? "Error: " : "Warning: ") + problem.subject()).withStyle(error ? ChatFormatting.RED : ChatFormatting.YELLOW),
					wrap(problem.message()), null, false));
		}
		return items;
	}

	private static List<Item> aboutItems() {
		FabricLoader loader = FabricLoader.getInstance();
		List<Item> items = new ArrayList<>();
		items.add(info("version", Items.NETHER_STAR, "MejtaCore " + MejtaCore.version(), gray("Public API version " + MejtaCoreAPI.API_VERSION)));
		items.add(info("minecraft", Items.GRASS_BLOCK, "Minecraft " + modVersion(loader, "minecraft")));
		items.add(info("loader", Items.CRAFTING_TABLE, "Fabric Loader " + modVersion(loader, "fabricloader")));
		items.add(info("fabric_api", Items.ANVIL, "Fabric API " + modVersion(loader, "fabric-api")));
		items.add(info("addons", Items.CHEST, "Addons", gray(addonSummary())));
		items.add(info("config_dir", Items.BOOKSHELF, "Configuration Folder", gray("config/mejta/")));
		items.add(info("commands", Items.COMMAND_BLOCK, "Commands", gray("/mejta opens this menu"), gray("/mejta help lists every command"),
				gray("/mejta config opens the settings editor")));
		return items;
	}

	private static String addonSummary() {
		Collection<AddonInfo> addons = MejtaCoreAPI.getAddons();
		long active = addons.stream().filter(AddonInfo::isActive).count();
		long failed = addons.size() - active;
		return addons.isEmpty() ? "No addons installed" : active + " addon(s) active" + (failed > 0 ? ", " + failed + " failed" : "");
	}

	private static String modVersion(FabricLoader loader, String modId) {
		return loader.getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}

	private static Card info(String id, net.minecraft.world.item.Item icon, String title, Component... lines) {
		return new Card(id, new ItemStack(icon), Component.literal(title).withStyle(ChatFormatting.AQUA), List.of(lines), null, false);
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
}
