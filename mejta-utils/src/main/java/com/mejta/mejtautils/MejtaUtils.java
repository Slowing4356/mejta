package com.mejta.mejtautils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;

import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.storage.LevelResource;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtautils.freeze.FreezeCommands;
import com.mejta.mejtautils.freeze.FreezeHooks;
import com.mejta.mejtautils.freeze.FreezeManager;
import com.mejta.mejtautils.inspect.Inspect;
import com.mejta.mejtautils.inspect.OfflineMenu;
import com.mejta.mejtautils.offline.PendingChange;
import com.mejta.mejtautils.offline.PendingChanges;
import com.mejta.mejtautils.staffmode.StaffModeCommands;
import com.mejta.mejtautils.staffmode.StaffModeManager;
import com.mejta.mejtautils.staffmode.StaffTools;
import com.mejta.mejtautils.util.ScopedDataStore;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.vanish.VanishCommands;
import com.mejta.mejtautils.vanish.VanishManager;

/**
 * MejtaUtils entrypoint. Registers the addon with MejtaCore (config, permissions, commands), then wires
 * the admin tools into the game's events. Server-side only; vanilla clients need nothing.
 */
public final class MejtaUtils implements MejtaAddon {
	public static final String ID = "mejtautils";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile ScopedDataStore store;
	private static volatile PendingChanges pending;
	private static volatile VanishManager vanish;
	private static volatile FreezeManager freeze;
	private static volatile StaffModeManager staffMode;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaUtils")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Admin tools: inventory inspection, status panel, vanish, freeze, staff mode")
				.icon("minecraft:spyglass")
				.config(UtilsSettings.SPEC)
				.permissions(UtilsPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					store = new ScopedDataStore(MejtaCoreAPI.dataStore(ID));
					pending = new PendingChanges(store);
					vanish = new VanishManager(store, config);
					freeze = new FreezeManager(store, config);
					staffMode = new StaffModeManager(store, config);
					MejtaCoreAPI.presence().registerVisibilityProvider(ID, vanish);

					Inspect.registerCommands();
					VanishCommands.register();
					FreezeCommands.register();
					StaffModeCommands.register();
					MejtaCoreAPI.registerServerCommands(ID, MejtaUtils::adminCommands);
					registerEvents();
					log.info("MejtaUtils initialized");
				})
				.build());
	}

	private static void registerEvents() {
		FreezeHooks.register();
		ServerLifecycleEvents.SERVER_STARTING.register(MejtaUtils::serverStarting);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			resetState(false);
			store.unscope();
		});
		// Fabric's player JOIN fires at the end of placing the player: they are in the player list (so
		// saving the players includes them) and in the world, before their first tick. The connection
		// JOIN event comes earlier, before the player is in the list.
		ServerPlayerEvents.JOIN.register(player -> {
			// Order matters: queued offline changes act on the saved inventory, then staff mode may replace it.
			OfflineMenu.closeAllFor(player.getUUID());
			pending.onJoin(player);
			staffMode.onJoin(player);
			vanish.onJoin(player);
			freeze.onJoin(player);
		});
		// The connection is set up before the join message, the player list update and entity tracking, which
		// all ask whether the player is vanished: their state is read from disk once here, not once per check.
		ServerPlayConnectionEvents.INIT.register((handler, server) -> vanish.preload(handler.player.getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> freeze.onLeave(handler.player));
		// After the leave message (which the leave mixin hides for vanished players) and after the
		// connection's DISCONNECT, where MejtaCore's presence still sees the player as hidden.
		ServerPlayerEvents.LEAVE.register(player -> vanish.onLeave(player));
		// Before any drop: a death in staff mode drops nothing (the survival state is in the snapshot).
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				staffMode.onFatalDamage(player);
			}
			return true;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> freeze.tick(server));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> StaffTools.discardIfTool(entity));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			staffMode.onRespawn(newPlayer);
			freeze.onRespawn(newPlayer);
		});
		UseItemCallback.EVENT.register((player, level, hand) ->
				player instanceof ServerPlayer sp ? StaffTools.useInAir(sp, hand) : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				player instanceof ServerPlayer sp ? StaffTools.useOnEntity(sp, hand, entity) : InteractionResult.PASS);
	}

	/**
	 * A dedicated server uses the data store's keys as they are. An integrated server (singleplayer, LAN)
	 * may open several worlds in one game session, and the data store is shared by all of them, so its
	 * keys are put under the world's own folder ({@code worlds/<world id>/}). Every cache is reloaded.
	 */
	private static void serverStarting(MinecraftServer server) {
		if (server.isDedicatedServer()) {
			store.unscope();
		} else {
			Path players = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
			Path world = players.getParent() != null ? players.getParent() : players;
			Path folder = world.getFileName();
			store.scopeToWorld(ScopedDataStore.worldId(folder != null ? folder.toString() : "world", world.toString()));
		}
		resetState(true);
		log.info("MejtaUtils data scope '{}': {} frozen, {} staff-mode session(s) to recover",
				store.scope().isEmpty() ? "server" : store.scope(), freeze.all().size(), staffMode.sessions().size());
	}

	private static void resetState(boolean load) {
		OfflineMenu.forgetAll();
		vanish.reset();
		freeze.reset(load);
		staffMode.reset(load);
	}

	// ---------------------------------------------------------------- /mejtaadmin mejtautils

	private static void adminCommands(com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root,
			net.minecraft.commands.CommandBuildContext buildContext) {
		root.then(Commands.literal("pending")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.INVSEE_OFFLINE))
						.executes(MejtaUtils::listPending)
						.then(Commands.literal("cancel")
								.requires(MejtaCoreAPI.requires(UtilsPermissions.INVSEE_OFFLINE_EDIT))
								.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> cancelPending(ctx, false))))
						.then(Commands.literal("discard")
								.requires(MejtaCoreAPI.requires(UtilsPermissions.ADMIN))
								.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> cancelPending(ctx, true)))))
				.then(Commands.literal("sessions")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.STAFFMODE_RESTORE))
						.executes(MejtaUtils::listSessions));
	}

	private static int listPending(CommandContext<CommandSourceStack> ctx) {
		List<PendingChange> all = pending.all();
		ctx.getSource().sendSystemMessage(Component.literal(all.size() + " queued offline change(s):").withStyle(ChatFormatting.GOLD));
		for (PendingChange c : all) {
			ctx.getSource().sendSystemMessage(Component.literal(" " + c.targetName() + ": " + c.state().name().toLowerCase(java.util.Locale.ROOT)
					+ ", " + c.ops().size() + " slot(s), by " + c.staffName() + ", " + c.claims().size() + " unclaimed item(s)"
					+ (c.note().isEmpty() ? "" : " (" + c.note() + ")")).withStyle(ChatFormatting.GRAY));
		}
		return all.size();
	}

	private static int cancelPending(CommandContext<CommandSourceStack> ctx, boolean discard) throws CommandSyntaxException {
		Targets.Target target = Targets.single(ctx, "player");
		try {
			boolean done = discard
					? pending.discard(target.id(), MejtaCoreAPI.actor(ctx.getSource()))
					: pending.cancel(target.id(), MejtaCoreAPI.actor(ctx.getSource()), "cancelled by " + ctx.getSource().getTextName());
			if (!done) {
				ctx.getSource().sendFailure(Component.literal(discard ? "No queued change for " + target.name() + "."
						: "No open queued change for " + target.name() + " (a change interrupted while applying needs 'discard' after checking the player)."));
				return 0;
			}
		} catch (IOException e) {
			log.error("Could not update queued change for {}", target.name(), e);
			ctx.getSource().sendFailure(Component.literal("Could not save; see the server log."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal((discard ? "Discarded" : "Cancelled") + " the queued change for " + target.name()
				+ (discard ? "." : "; added items went back to the staff member's claims.")), false);
		return 1;
	}

	private static int listSessions(CommandContext<CommandSourceStack> ctx) {
		List<JsonObject> sessions = staffMode.sessions();
		ctx.getSource().sendSystemMessage(Component.literal(sessions.size() + " staff-mode session(s):").withStyle(ChatFormatting.GOLD));
		for (JsonObject s : sessions) {
			ctx.getSource().sendSystemMessage(Component.literal(" " + s.get("name").getAsString() + ": " + s.get("state").getAsString()
					+ " since " + s.get("started").getAsString()).withStyle(ChatFormatting.GRAY));
		}
		return sessions.size();
	}

	// ---------------------------------------------------------------- accessors

	public static ConfigHandle config() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	/** MejtaUtils' data store, limited to the current world on an integrated server. */
	public static ScopedDataStore store() {
		return store;
	}

	public static PendingChanges pending() {
		return pending;
	}

	public static VanishManager vanish() {
		return vanish;
	}

	/** {@code null} before initialization or if MejtaUtils failed: mixins then change nothing. */
	public static VanishManager vanishOrNull() {
		return vanish;
	}

	public static FreezeManager freeze() {
		return freeze;
	}

	public static FreezeManager freezeOrNull() {
		return freeze;
	}

	public static StaffModeManager staffMode() {
		return staffMode;
	}

	public static StaffModeManager staffModeOrNull() {
		return staffMode;
	}
}
