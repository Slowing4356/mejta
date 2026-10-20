package com.mejta.mejtasmp;

import java.nio.file.Path;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
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
import com.mejta.mejtasmp.afk.AfkProtection;
import com.mejta.mejtasmp.antialt.AntiAlt;
import com.mejta.mejtasmp.scoreboard.ScoreMenus;
import com.mejta.mejtasmp.scoreboard.Scoreboards;
import com.mejta.mejtasmp.stats.StatBook;
import com.mejta.mejtasmp.util.ScopedDataStore;
import com.mejta.mejtasmp.world.Dimensions;
import com.mejta.mejtasmp.world.WorldRules;

/**
 * MejtaSMP entrypoint: SMP gameplay settings for servers running MejtaCore. Server-side only; vanilla
 * clients need nothing.
 */
public final class MejtaSMP implements MejtaAddon {
	public static final String ID = "mejtasmp";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile ScopedDataStore store;
	private static volatile Scoreboards scoreboards;
	private static volatile AntiAlt antiAlt;
	private static volatile MinecraftServer server;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaSMP")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("SMP features: AFK protection, scoreboards, item and XP clumping, infinite trades, sleep, dimensions, account limit")
				.icon("minecraft:totem_of_undying")
				.config(SmpSettings.SPEC)
				.permissions(SmpPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					store = new ScopedDataStore(MejtaCoreAPI.dataStore(ID));
					scoreboards = new Scoreboards(config, new StatBook(store), store);
					antiAlt = new AntiAlt(config, store);
					ScoreMenus.register();
					MejtaCoreAPI.registerServerCommands(ID, (root, buildContext) -> root
							.then(Commands.literal("status")
									.requires(MejtaCoreAPI.requires(SmpPermissions.ADMIN))
									.executes(c -> {
										c.getSource().sendSystemMessage(Component.literal("MejtaSMP: " + scoreboards.book().size() + " player(s) with stats, "
												+ antiAlt.counted() + " counted by the account limit; data scope "
												+ (store.scope().isEmpty() ? "server" : store.scope())).withStyle(ChatFormatting.GOLD));
										return 1;
									})));
					config.addChangeListener(MejtaSMP::settingsChanged);
					registerEvents();
					log.info("MejtaSMP initialized");
				})
				.build());
	}

	private static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(MejtaSMP::scope);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			scoreboards.book().load();
			WorldRules.apply(s, config);
			scoreboards.applyTabList(s);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			scoreboards.clear();
			scoreboards.book().clear();
			antiAlt.reset();
			store.unscope();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayer player = handler.player;
			if (antiAlt.onJoin(player)) {
				scoreboards.onJoin(player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
			ServerPlayer player = handler.player;
			antiAlt.onLeave(player.getUUID());
			Dimensions.forget(player.getUUID());
			scoreboards.onLeave(player);
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> scoreboards.tick(s));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> AfkProtection.allowDamage(config, entity, source));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer victim) {
				if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
					scoreboards.book().playerKilled(killer, victim);
				} else {
					scoreboards.book().died(victim);
				}
			}
		});
	}

	/** A setting changed (menu, command or file reload). Runs on the server thread. */
	private static void settingsChanged() {
		MinecraftServer s = server;
		if (s != null) {
			s.execute(() -> {
				WorldRules.apply(s, config);
				scoreboards.applyTabList(s);
				scoreboards.update(s);
			});
		}
	}

	/** Integrated servers (singleplayer, LAN) keep data per world; a dedicated server uses the keys as they are. */
	private static void scope(MinecraftServer s) {
		if (s.isDedicatedServer()) {
			store.unscope();
		} else {
			Path players = s.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
			Path world = players.getParent() != null ? players.getParent() : players;
			Path folder = world.getFileName();
			store.scopeToWorld(ScopedDataStore.worldId(folder != null ? folder.toString() : "world", world.toString()));
		}
	}

	public static ConfigHandle config() {
		return config;
	}

	/** {@code null} before initialization or if MejtaSMP failed: the mixins then change nothing. */
	public static ConfigHandle configOrNull() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	public static Scoreboards scoreboards() {
		return scoreboards;
	}

	public static AntiAlt antiAlt() {
		return antiAlt;
	}

	public static ScopedDataStore store() {
		return store;
	}
}
