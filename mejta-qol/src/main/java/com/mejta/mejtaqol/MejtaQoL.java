package com.mejta.mejtaqol;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtaqol.afk.AfkManager;
import com.mejta.mejtaqol.chat.Replies;
import com.mejta.mejtaqol.chat.ReplyCommand;
import com.mejta.mejtaqol.data.Prefs;
import com.mejta.mejtaqol.home.HomeCommands;
import com.mejta.mejtaqol.home.Homes;
import com.mejta.mejtaqol.misc.QolHelp;
import com.mejta.mejtaqol.misc.Welcome;
import com.mejta.mejtaqol.rtp.RandomTeleport;
import com.mejta.mejtaqol.spawn.SpawnCommands;
import com.mejta.mejtaqol.station.Stations;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.tpa.TpaCommands;
import com.mejta.mejtaqol.tpa.TpaService;
import com.mejta.mejtaqol.util.ScopedDataStore;
import com.mejta.mejtaqol.warp.WarpCommands;

/**
 * MejtaQoL entrypoint. Registers the addon with MejtaCore (settings, permissions, commands), then wires
 * the player features into the game's events. Server-side only; vanilla clients need nothing.
 */
public final class MejtaQoL implements MejtaAddon {
	public static final String ID = "mejtaqol";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile ScopedDataStore store;
	private static volatile Teleports teleports;
	private static volatile TpaService tpa;
	private static volatile Homes homes;
	private static volatile Prefs prefs;
	private static volatile RandomTeleport rtp;
	private static volatile AfkManager afk;
	private static volatile Replies replies;
	private static volatile MinecraftServer server;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaQoL")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Player commands: teleport requests, homes, warps, spawn, /back, /rtp, ender chest, workstations, AFK, /r")
				.icon("minecraft:ender_pearl")
				.config(QolSettings.SPEC)
				.permissions(QolPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					store = new ScopedDataStore(MejtaCoreAPI.dataStore(ID));
					teleports = new Teleports(config);
					prefs = new Prefs(store);
					homes = new Homes(store);
					tpa = new TpaService(config, teleports, prefs, id -> {
						MinecraftServer s = server;
						return s == null ? null : s.getPlayerList().getPlayer(id);
					});
					rtp = new RandomTeleport(config, teleports);
					afk = new AfkManager(config);
					replies = new Replies();

					TpaCommands.register();
					HomeCommands.register();
					SpawnCommands.register();
					WarpCommands.register();
					RandomTeleport.register();
					Stations.register();
					AfkManager.register();
					ReplyCommand.register();
					QolHelp.register();
					MejtaCoreAPI.registerServerCommands(ID, MejtaQoL::adminCommands);
					config.addChangeListener(MejtaQoL::settingsChanged);
					registerEvents();
					log.info("MejtaQoL initialized");
				})
				.build());
	}

	private static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(MejtaQoL::serverStarting);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			resetState();
			store.unscope();
		});
		ServerPlayerEvents.JOIN.register(player -> {
			boolean first = Welcome.isFirstJoin(player);
			Welcome.onJoin(player, first);
		});
		ServerPlayerEvents.LEAVE.register(player -> {
			teleports.onLeave(player.getUUID());
			tpa.onLeave(player.getUUID());
			rtp.onLeave(player.getUUID());
			homes.forget(player.getUUID());
			prefs.forget(player.getUUID());
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) ->
				teleports.onDamage(entity, source, damage));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				teleports.onLeave(player.getUUID());
				Welcome.onDeath(player);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			teleports.tick(s);
			rtp.tick(s);
			if (s.getTickCount() % 20 == 0) {
				tpa.tick();
				afk.tick(s);
			}
		});
	}

	/**
	 * A dedicated server uses the data store's keys as they are. An integrated server (singleplayer, LAN)
	 * may open several worlds in one game session, so its keys are put under the world's own folder.
	 */
	private static void serverStarting(MinecraftServer s) {
		server = s;
		if (s.isDedicatedServer()) {
			store.unscope();
		} else {
			Path players = s.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
			Path world = players.getParent() != null ? players.getParent() : players;
			Path folder = world.getFileName();
			store.scopeToWorld(ScopedDataStore.worldId(folder != null ? folder.toString() : "world", world.toString()));
		}
		resetState();
		WarpCommands.load(store);
		log.info("MejtaQoL data scope '{}': {} warp(s)", store.scope().isEmpty() ? "server" : store.scope(), WarpCommands.book().size());
	}

	private static void resetState() {
		teleports.reset();
		tpa.reset();
		rtp.reset();
		replies.clear();
		homes.clear();
		prefs.clear();
	}

	/**
	 * A setting changed (menu, command or file reload): commands of a feature that was switched on or off
	 * appear or disappear, and the AFK tags follow afk.tab_list. Runs on the server thread.
	 */
	private static void settingsChanged() {
		MinecraftServer s = server;
		if (s == null) {
			return;
		}
		s.execute(() -> {
			for (ServerPlayer player : s.getPlayerList().getPlayers()) {
				s.getCommands().sendCommands(player);
				afk.refreshTabList(player);
			}
		});
	}

	// ---------------------------------------------------------------- /mejtaadmin mejtaqol

	private static void adminCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		root.then(Commands.literal("status")
				.requires(MejtaCoreAPI.requires(QolPermissions.ADMIN))
				.executes(MejtaQoL::status));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack s = ctx.getSource();
		s.sendSystemMessage(Component.literal("MejtaQoL").withStyle(ChatFormatting.GOLD));
		s.sendSystemMessage(Component.literal(" Open teleport requests: " + tpa.requests().size()).withStyle(ChatFormatting.GRAY));
		s.sendSystemMessage(Component.literal(" Random teleports searching: " + rtp.searching()).withStyle(ChatFormatting.GRAY));
		s.sendSystemMessage(Component.literal(" AFK players: " + afk.afkCount(s.getServer())).withStyle(ChatFormatting.GRAY));
		s.sendSystemMessage(Component.literal(" Warps: " + WarpCommands.book().size()).withStyle(ChatFormatting.GRAY));
		s.sendSystemMessage(Component.literal(" /setspawn point: " + SpawnCommands.customSpawn(store).map(l -> l.describe()).orElse("not set (world spawn)"))
				.withStyle(ChatFormatting.GRAY));
		s.sendSystemMessage(Component.literal(" Data scope: " + (store.scope().isEmpty() ? "server" : store.scope())).withStyle(ChatFormatting.GRAY));
		return 1;
	}

	// ---------------------------------------------------------------- accessors

	public static ConfigHandle config() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	/** MejtaQoL's data store, limited to the current world on an integrated server. */
	public static ScopedDataStore store() {
		return store;
	}

	public static Teleports teleports() {
		return teleports;
	}

	public static TpaService tpa() {
		return tpa;
	}

	public static Homes homes() {
		return homes;
	}

	public static Prefs prefs() {
		return prefs;
	}

	public static RandomTeleport rtp() {
		return rtp;
	}

	public static AfkManager afk() {
		return afk;
	}

	/** {@code null} before initialization or if MejtaQoL failed: the mixins then change nothing. */
	public static AfkManager afkOrNull() {
		return afk;
	}

	public static Replies replies() {
		return replies;
	}

	public static Replies repliesOrNull() {
		return replies;
	}
}
