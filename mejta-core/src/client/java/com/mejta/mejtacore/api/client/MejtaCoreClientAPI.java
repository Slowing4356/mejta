package com.mejta.mejtacore.api.client;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.addons.ClientAddonExtensions;
import com.mejta.mejtacore.ui.ChestMenuScreen;
import com.mejta.mejtacore.ui.CoreMenus;
import com.mejta.mejtacore.ui.ScreenOpener;

/**
 * Client-side part of the MejtaCore API: menu entries, client commands, opening menus.
 *
 * <p>These methods may be called at any time on the client (typically from a
 * {@code "mejtacore-client"} entrypoint, see {@link MejtaClientAddon}). Contributions of an addon
 * that is not registered or failed to initialize are ignored when menus/commands are built.
 */
public final class MejtaCoreClientAPI {
	private MejtaCoreClientAPI() {
	}

	/**
	 * Adds an entry to the MejtaCore main menu and to the addon's page in "Installed Addons".
	 *
	 * @return {@code false} if the addon already has an entry with the same ID
	 */
	public static boolean registerMenuEntry(String addonId, MenuEntry entry) {
		return ClientAddonExtensions.addMenuEntry(addonId, entry);
	}

	/**
	 * Adds client subcommands under {@code /mejtacore <addonId>} and {@code /mejta <addonId>}.
	 * Takes effect the next time the client command tree is built (joining a world or server).
	 */
	public static void registerCommands(String addonId, ClientCommandProvider provider) {
		ClientAddonExtensions.addCommands(addonId, provider);
	}

	/** Opens the MejtaCore main menu on the next client tick. */
	public static void openMainMenu() {
		ScreenOpener.open(CoreMenus::mainMenu);
	}

	/**
	 * Opens a standalone chest-style page on the next client tick. {@code null} elements in the
	 * list leave a slot empty; extra entries are split into pages automatically.
	 */
	public static void openPage(Component title, Supplier<List<MenuEntry>> entries) {
		ScreenOpener.open(() -> new ChestMenuScreen(title, entries, null));
	}
}
