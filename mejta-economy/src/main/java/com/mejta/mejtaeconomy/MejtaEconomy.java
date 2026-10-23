package com.mejta.mejtaeconomy;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.storage.RecordLogOptions;

/**
 * MejtaEconomy entrypoint. Registers the addon with MejtaCore and provides MejtaCore's shared economy
 * ({@code MejtaCoreAPI.economy()}), which shops, crates and auction houses use. Server-side only.
 */
public final class MejtaEconomy implements MejtaAddon {
	public static final String ID = "mejtaeconomy";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile EcoProvider provider;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaEconomy")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Player balances, /pay and /baltop; the economy other addons use")
				.icon("minecraft:gold_ingot")
				.config(EconomySettings.SPEC)
				.permissions(EconomyPermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					provider = new EcoProvider(config, MejtaCoreAPI.dataStore(ID),
							MejtaCoreAPI.recordLog(ID, "transactions", new RecordLogOptions(8L * 1024 * 1024, 10, 0)), log);
					MejtaCoreAPI.economy().registerProvider(ID, provider);
					EconomyCommands.register();
					registerEvents();
					log.info("MejtaEconomy initialized");
				})
				.build());
	}

	private static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> provider.serverStarting(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> provider.serverStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> provider.serverStopped());
		ServerTickEvents.END_SERVER_TICK.register(server -> provider.tick());
		ServerPlayerEvents.JOIN.register(player -> {
			long away = provider.joined(player);
			if (away > 0 && config.get(EconomySettings.AWAY_SUMMARY)) {
				player.sendSystemMessage(Component.literal("While you were away you received ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(provider.format(away)).withStyle(ChatFormatting.GREEN))
						.append(Component.literal(". Balance: ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(provider.format(provider.balance(player.getUUID()))).withStyle(ChatFormatting.GOLD)));
			}
		});
	}

	public static ConfigHandle config() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	public static EcoProvider provider() {
		return provider;
	}
}
