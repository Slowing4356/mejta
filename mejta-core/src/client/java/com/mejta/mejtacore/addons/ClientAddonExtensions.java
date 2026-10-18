package com.mejta.mejtacore.addons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.client.ClientCommandProvider;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.logging.Diagnostics;

/** Stores client contributions (menu entries, commands) per addon ID. */
public final class ClientAddonExtensions {
	private static final Map<String, List<MenuEntry>> MENU_ENTRIES = new LinkedHashMap<>();
	private static final Map<String, List<ClientCommandProvider>> COMMANDS = new LinkedHashMap<>();

	private ClientAddonExtensions() {
	}

	public static synchronized boolean addMenuEntry(String addonId, MenuEntry entry) {
		Objects.requireNonNull(addonId, "addonId");
		Objects.requireNonNull(entry, "entry");
		List<MenuEntry> list = MENU_ENTRIES.computeIfAbsent(addonId, k -> new ArrayList<>());
		for (MenuEntry existing : list) {
			if (existing.id().equals(entry.id())) {
				Diagnostics.warn(addonId, "Duplicate menu entry id '" + entry.id() + "' ignored");
				return false;
			}
		}
		list.add(entry);
		return true;
	}

	public static synchronized void addCommands(String addonId, ClientCommandProvider provider) {
		Objects.requireNonNull(addonId, "addonId");
		Objects.requireNonNull(provider, "provider");
		COMMANDS.computeIfAbsent(addonId, k -> new ArrayList<>()).add(provider);
	}

	/** Menu entries of one addon, or empty if the addon is not active. */
	public static synchronized List<MenuEntry> menuEntries(String addonId) {
		if (!MejtaCoreAPI.isAddonActive(addonId)) {
			return List.of();
		}
		return List.copyOf(MENU_ENTRIES.getOrDefault(addonId, List.of()));
	}

	/** Menu entries of all active addons, in addon ID order. */
	public static synchronized List<MenuEntry> allMenuEntries() {
		List<MenuEntry> out = new ArrayList<>();
		MejtaCoreAPI.getAddons().forEach(addon -> out.addAll(menuEntries(addon.id())));
		return out;
	}

	/** Command providers of active addons, keyed by addon ID (sorted). Warns about unknown IDs. */
	public static synchronized Map<String, List<ClientCommandProvider>> activeCommandProviders() {
		Map<String, List<ClientCommandProvider>> out = new LinkedHashMap<>();
		MejtaCoreAPI.getAddons().forEach(addon -> {
			List<ClientCommandProvider> providers = COMMANDS.get(addon.id());
			if (providers != null && addon.isActive()) {
				out.put(addon.id(), List.copyOf(providers));
			}
		});
		return out;
	}

	/** Reports contributions made for addon IDs that were never registered. */
	public static synchronized void reportOrphans() {
		for (String id : MENU_ENTRIES.keySet()) {
			if (MejtaCoreAPI.getAddon(id).isEmpty()) {
				Diagnostics.warn(id, "Menu entries registered for unknown addon '" + id + "' are ignored");
			}
		}
		for (String id : COMMANDS.keySet()) {
			if (MejtaCoreAPI.getAddon(id).isEmpty()) {
				Diagnostics.warn(id, "Commands registered for unknown addon '" + id + "' are ignored");
			}
		}
	}
}
