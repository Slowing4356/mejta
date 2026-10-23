package com.mejta.mejtamarket;

import java.nio.file.Path;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtamarket.bot.MarketBot;
import com.mejta.mejtamarket.data.MarketStore;
import com.mejta.mejtamarket.menu.MarketMenus;
import com.mejta.mejtamarket.price.PriceFile;
import com.mejta.mejtamarket.util.ScopedDataStore;

/**
 * MejtaMarket entrypoint: an auction house with buy orders and a market bot, paid through MejtaCore's
 * shared economy. Server-side only; vanilla clients need nothing.
 */
public final class MejtaMarket implements MejtaAddon {
	public static final String ID = "mejtamarket";
	/** Expired listings and orders are checked this often (ticks). */
	private static final int EXPIRE_TICKS = 200;
	/** The price history and the bot's ledger are saved this often (ticks), besides after each trade. */
	private static final int SAVE_TICKS = 6000;

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile ScopedDataStore store;
	private static volatile MarketService market;
	private static volatile MarketMenus menus;
	private static volatile MarketBot bot;
	private static volatile PriceFile priceFile;
	private static int ticks;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaMarket")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Auction house with buy orders and a market bot that keeps quiet servers trading")
				.icon("minecraft:emerald")
				.config(MarketSettings.SPEC)
				.permissions(MarketPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					store = new ScopedDataStore(MejtaCoreAPI.dataStore(ID));
					market = new MarketService(config, new MarketStore(store),
							MejtaCoreAPI.recordLog(ID, "trades", new RecordLogOptions(5L << 20, 10, 365)));
					menus = new MarketMenus(market);
					bot = new MarketBot(market);
					priceFile = new PriceFile(FabricLoader.getInstance().getConfigDir().resolve("mejta").resolve(ID + "-prices.json"), log);
					MarketCommands.register(market, menus, bot, priceFile);
					registerEvents();
					log.info("MejtaMarket initialized");
				})
				.build());
	}

	private static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(MejtaMarket::scope);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			market.serverStarted(server, priceFile.load());
			if (!MejtaCoreAPI.economy().isAvailable()) {
				log.warn("No economy provider (such as MejtaEconomy) is running: the market stays closed");
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			market.serverStopped();
			store.unscope();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (market.server() == null) {
				return;
			}
			if (++ticks % EXPIRE_TICKS == 0) {
				market.expire();
			}
			if (ticks % SAVE_TICKS == 0) {
				market.saveState();
			}
			bot.tick(server);
		});
		ServerPlayerEvents.JOIN.register(player -> {
			if (market.server() != null) {
				market.onJoin(player);
			}
		});
		ServerPlayerEvents.LEAVE.register(player -> MarketMenus.forget(player.getUUID()));
	}

	/** Integrated servers (singleplayer, LAN) keep a market per world; a dedicated server uses the keys as they are. */
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

	public static Logger log() {
		return log;
	}

	public static ConfigHandle config() {
		return config;
	}

	/** {@code null} before initialization or if MejtaMarket failed. */
	public static MarketService market() {
		return market;
	}

	public static MarketMenus menus() {
		return menus;
	}

	public static MarketBot bot() {
		return bot;
	}
}
