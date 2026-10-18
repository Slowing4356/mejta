package com.mejta.mejtaac;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.mejta.mejtaac.command.AcCommands;
import com.mejta.mejtaac.record.AcRecords;
import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.storage.RecordLogOptions;

/**
 * MejtaAC entrypoint. Registers the addon with MejtaCore, which loads {@code config/mejta/mejtaac.json},
 * then wires the anti-cheat into server commands and connection events.
 */
public final class MejtaAC implements MejtaAddon {
	public static final String ID = "mejtaac";

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaAC")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Server-side anti-cheat")
				.icon("minecraft:shield")
				.config(AcSettings.SPEC)
				.permissions(AcPermissions.ALERTS, AcPermissions.ADMIN, AcPermissions.BYPASS)
				.onInitialize(ctx -> {
					ConfigHandle config = ctx.config().orElseThrow();
					RecordLogOptions options = new RecordLogOptions(
							config.get(AcSettings.RECORDS_MAX_FILE_MB) * 1024L * 1024L,
							config.get(AcSettings.RECORDS_MAX_FILES),
							config.get(AcSettings.RECORDS_RETENTION_DAYS));
					AntiCheat antiCheat = new AntiCheat(ID, config, new AcRecords(ID, options), ctx.logger());
					AntiCheat.install(antiCheat);

					MejtaCoreAPI.registerServerCommands(ID, AcCommands::register);
					ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> antiCheat.onJoin(handler.player, server));
					ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> antiCheat.onDisconnect(handler.player.getUUID()));
					ServerTickEvents.END_SERVER_TICK.register(antiCheat::onServerTickEnd);

					ctx.logger().info("MejtaAC initialized (enforcement {}, records in {})",
							config.get(AcSettings.ENFORCEMENT_MODE), MejtaCoreAPI.dataDirectory(ID));
				})
				.build());
	}
}
