package com.mejta.mejtacorpse;

import java.nio.file.Path;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
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
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.data.CorpseStore;
import com.mejta.mejtacorpse.util.ScopedDataStore;
import com.mejta.mejtacorpse.world.CorpseEntities;

/**
 * MejtaCorpse entrypoint. Registers the addon with MejtaCore (settings, permissions, commands), then
 * wires corpses into the game's events. Server-side only; vanilla clients need nothing.
 */
public final class MejtaCorpse implements MejtaAddon {
	public static final String ID = "mejtacorpse";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile ScopedDataStore store;
	private static volatile CorpseManager manager;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaCorpse")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Corpses that keep a dead player's items, opened like a chest")
				.icon("minecraft:skeleton_skull")
				.config(CorpseSettings.SPEC)
				.permissions(CorpsePermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					store = new ScopedDataStore(MejtaCoreAPI.dataStore(ID));
					manager = new CorpseManager(config, new CorpseStore(store));
					CorpseCommands.register();
					MejtaCoreAPI.registerServerCommands(ID, (root, buildContext) -> root
							.then(Commands.literal("status")
									.requires(MejtaCoreAPI.requires(CorpsePermissions.ADMIN))
									.executes(c -> {
										long active = manager.store().all().stream().filter(r -> r.state() == CorpseRecord.State.ACTIVE).count();
										c.getSource().sendSystemMessage(Component.literal("MejtaCorpse: " + manager.store().size() + " corpse(s), "
												+ active + " active, " + manager.liveCount() + " open; data scope "
												+ (store.scope().isEmpty() ? "server" : store.scope())).withStyle(ChatFormatting.GOLD));
										return 1;
									})));
					registerEvents();
					log.info("MejtaCorpse initialized");
				})
				.build());
	}

	private static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(MejtaCorpse::scope);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> manager.serverStarted(server));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			manager.serverStopped();
			store.unscope();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> manager.tick(server));
		ServerPlayerEvents.JOIN.register(player -> manager.onJoin(player));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> manager.onEntityLoad(entity));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> manager.onEntityUnload(entity));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				player instanceof ServerPlayer sp ? manager.onUse(sp, entity, hand) : InteractionResult.PASS);
		// Corpses cannot be hit.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				CorpseEntities.corpseId(entity).isPresent() ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/** Integrated servers (singleplayer, LAN) keep corpses per world; a dedicated server uses the keys as they are. */
	private static void scope(MinecraftServer server) {
		if (server.isDedicatedServer()) {
			store.unscope();
		} else {
			Path players = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
			Path world = players.getParent() != null ? players.getParent() : players;
			Path folder = world.getFileName();
			store.scopeToWorld(ScopedDataStore.worldId(folder != null ? folder.toString() : "world", world.toString()));
		}
	}

	public static ConfigHandle config() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	/** MejtaCorpse's data store, limited to the current world on an integrated server. */
	public static ScopedDataStore store() {
		return store;
	}

	public static CorpseManager manager() {
		return manager;
	}

	/** {@code null} before initialization or if MejtaCorpse failed: the death hook then changes nothing. */
	public static CorpseManager managerOrNull() {
		return manager;
	}
}
