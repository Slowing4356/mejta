package com.mejta.mejtacore.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.addons.AddonRegistry;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.commands.ServerCommands;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.notify.NotificationBus;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.storage.RecordLogs;

/**
 * Entry point of the MejtaCore public API (common side: works on client and dedicated server).
 *
 * <h2>Registering an addon</h2>
 * Either declare a {@code "mejtacore"} entrypoint implementing {@link MejtaAddon} in your
 * {@code fabric.mod.json} (recommended), or call {@link #registerAddon(AddonDefinition)} from your
 * own {@code ModInitializer#onInitialize()}. Both ways work regardless of mod load order: MejtaCore
 * collects every registration made during the {@code main} initialization phase and resolves them
 * (dependency ordering, configuration loading, init callbacks) once that phase is over.
 *
 * <p>Client-only features (menu entries, client commands) are registered through
 * {@code com.mejta.mejtacore.api.client.MejtaCoreClientAPI}.
 *
 * <h2>Server features (API version 2)</h2>
 * Server commands under {@code /mejtaadmin}, permissions based on operator levels, notifications
 * and size-bounded record logs. They work on dedicated and integrated servers.
 */
public final class MejtaCoreAPI {
	/**
	 * Version of the public API contract. Incremented when the API changes in a way addons may need
	 * to check for. Addons should prefer a {@code fabric.mod.json} version range on {@code mejtacore}.
	 */
	public static final int API_VERSION = 2;

	private MejtaCoreAPI() {
	}

	/**
	 * Registers an addon. Must be called during the {@code main} initialization phase (an addon's
	 * {@code onInitialize()} or a {@code "mejtacore"} entrypoint).
	 *
	 * @return {@code true} if the registration was accepted; {@code false} if it was rejected
	 *         (duplicate ID, registration window closed). The reason is logged and shown in
	 *         {@code /mejtacore addons}.
	 */
	public static boolean registerAddon(AddonDefinition definition) {
		return AddonRegistry.get().register(definition, "MejtaCoreAPI.registerAddon");
	}

	/** Returns the addon with the given ID, if it was registered (in any state). */
	public static Optional<AddonInfo> getAddon(String id) {
		return AddonRegistry.get().find(id);
	}

	/** Returns {@code true} if the addon exists and initialized successfully. */
	public static boolean isAddonActive(String id) {
		return getAddon(id).map(AddonInfo::isActive).orElse(false);
	}

	/** All registered addons (active and failed), sorted by ID. */
	public static Collection<AddonInfo> getAddons() {
		return AddonRegistry.get().all();
	}

	/** {@code true} once registration is closed and addons have been initialized. */
	public static boolean isReady() {
		return AddonRegistry.get().isFinalized();
	}

	/** Returns a logger named {@code MejtaCore/<name>} for use by an addon. */
	public static Logger logger(String name) {
		return MejtaLog.forAddon(name);
	}

	/** The installed MejtaCore mod version, e.g. {@code 1.1.0}. */
	public static String coreVersion() {
		return MejtaCore.version();
	}

	// ---------------------------------------------------------------- server commands (API 2)

	/**
	 * Adds server subcommands under {@code /mejtaadmin <addonId>}. Call during initialization (an
	 * addon's init callback is a good place); takes effect when the server builds its command tree.
	 * Commands of addons that failed to initialize are left out.
	 */
	public static void registerServerCommands(String addonId, ServerCommandProvider provider) {
		ServerCommands.addProvider(addonId, provider);
	}

	// ---------------------------------------------------------------- permissions (API 2)

	/** Whether the command source has {@code permission} (console and command blocks: per their level). */
	public static boolean hasPermission(CommandSourceStack source, MejtaPermission permission) {
		return PermissionRegistry.check(source.permissions(), permission);
	}

	public static boolean hasPermission(ServerPlayer player, MejtaPermission permission) {
		return PermissionRegistry.check(player.permissions(), permission);
	}

	/** For Brigadier: {@code Commands.literal("x").requires(MejtaCoreAPI.requires(MY_PERMISSION))}. */
	public static Predicate<CommandSourceStack> requires(MejtaPermission permission) {
		return source -> hasPermission(source, permission);
	}

	// ---------------------------------------------------------------- notifications (API 2)

	public static NotificationService notifications() {
		return NotificationBus.get();
	}

	// ---------------------------------------------------------------- storage (API 2)

	/**
	 * Folder for an addon's runtime data ({@code <game dir>/mejta-data/<addonId>/}). Not created
	 * until something is written to it.
	 *
	 * @throws IllegalArgumentException if no addon with that ID is registered
	 */
	public static Path dataDirectory(String addonId) {
		return RecordLogs.dataDirectory(addonId);
	}

	/**
	 * Opens (or returns the already open) record log {@code <name>.jsonl} in the addon's data
	 * folder.
	 *
	 * @param name lowercase letters, digits, {@code _} and {@code -}
	 * @throws IllegalArgumentException if no addon with that ID is registered or the name is invalid
	 */
	public static RecordLog recordLog(String addonId, String name, RecordLogOptions options) {
		return RecordLogs.open(addonId, name, options);
	}
}
