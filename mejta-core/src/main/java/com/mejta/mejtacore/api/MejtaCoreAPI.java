package com.mejta.mejtacore.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.addons.AddonRegistry;
import com.mejta.mejtacore.audit.AuditTrail;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditService;
import com.mejta.mejtacore.api.metrics.Metrics;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.presence.PresenceService;
import com.mejta.mejtacore.api.server.Scheduler;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.commands.ServerCommands;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.core.TickScheduler;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.metrics.MetricRegistry;
import com.mejta.mejtacore.notify.NotificationBus;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.presence.Presence;
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
 *
 * <h2>API version 4 (MejtaCore 1.3.0)</h2>
 * Top-level commands ({@link #registerRootCommand}), a JSON data store ({@link #dataStore}), the
 * shared audit log ({@link #audit()}), presence for vanish-aware addons ({@link #presence()}), a
 * server-thread scheduler ({@link #scheduler()}), metrics ({@link #metrics()}), server-side menus
 * ({@code com.mejta.mejtacore.api.menu.ServerMenu}), and secret and experimental settings.
 */
public final class MejtaCoreAPI {
	/**
	 * Version of the public API contract. Incremented when the API changes in a way addons may need
	 * to check for. Addons should prefer a {@code fabric.mod.json} version range on {@code mejtacore}.
	 */
	public static final int API_VERSION = 4;

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

	/**
	 * Adds a top-level server command such as {@code /invsee}. The provider receives the command's
	 * literal node; gate it with {@code requires(MejtaCoreAPI.requires(permission))}.
	 *
	 * <p>The same command is always also available as {@code /mejtaadmin <addonId> <name>}. The top-level
	 * name is not registered if Minecraft, another mod or another addon already uses it (a warning is
	 * logged), or if the server owner listed it in {@code commands.disabled} in {@code mejtacore.json}.
	 * Note that a mod registering the same name <i>after</i> MejtaCore cannot be detected.
	 *
	 * @param name lowercase letters, digits, {@code _} and {@code -}; at most 32 characters
	 * @since API version 4
	 */
	public static void registerRootCommand(String addonId, String name, ServerCommandProvider provider) {
		ServerCommands.addRootCommand(addonId, name, provider);
	}

	// ---------------------------------------------------------------- permissions (API 2)

	/** Whether the command source has {@code permission} (console and command blocks: per their level). */
	public static boolean hasPermission(CommandSourceStack source, MejtaPermission permission) {
		return PermissionRegistry.check(source.permissions(), permission);
	}

	public static boolean hasPermission(ServerPlayer player, MejtaPermission permission) {
		return PermissionRegistry.check(player.permissions(), permission);
	}

	/**
	 * Whether a player who may be offline has {@code permission}, judged by the server's operator list
	 * (the same level they would have when online). Unknown players have level 0.
	 *
	 * @since API version 4
	 */
	public static boolean hasPermission(MinecraftServer server, NameAndId profile, MejtaPermission permission) {
		return PermissionRegistry.checkLevel(server.getProfilePermissions(profile).level(), permission);
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

	/**
	 * The addon's JSON data store, {@code mejta-data/<addonId>/store/}.
	 *
	 * @throws IllegalArgumentException if no addon with that ID is registered
	 * @since API version 4
	 */
	public static DataStore dataStore(String addonId) {
		return RecordLogs.dataStore(addonId);
	}

	// ---------------------------------------------------------------- audit, presence, scheduler (API 4)

	/** @since API version 4 */
	public static AuditService audit() {
		return AuditTrail.get();
	}

	/** The actor behind a command: the player, or the console/command block by its name. @since API version 4 */
	public static AuditActor actor(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player != null ? actor(player) : AuditActor.server(source.getTextName());
	}

	/** @since API version 4 */
	public static AuditActor actor(ServerPlayer player) {
		return AuditActor.player(player.getGameProfile().name(), player.getUUID());
	}

	/** @since API version 4 */
	public static PresenceService presence() {
		return Presence.get();
	}

	/** @since API version 4 */
	public static Scheduler scheduler() {
		return TickScheduler.get();
	}

	/** Timers, counters and gauges for performance tools such as MejtaPerf. @since API version 4 */
	public static Metrics metrics() {
		return MetricRegistry.get();
	}
}
