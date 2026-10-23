package com.mejta.mejtacrates;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.CrateStore;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.util.ItemJson;

/**
 * MejtaCrates entrypoint. Registers the addon with MejtaCore (settings, permissions, commands), then wires
 * crates into the game's events. Server-side only; vanilla clients need nothing.
 */
public final class MejtaCrates implements MejtaAddon {
	public static final String ID = "mejtacrates";

	private static volatile ConfigHandle config;
	private static volatile Logger log;
	private static volatile CrateService service;

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("MejtaCrates")
				.version(FabricLoader.getInstance().getModContainer(ID)
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown"))
				.description("Crates opened with keys, with weighted rewards and an opening animation")
				.icon("minecraft:tripwire_hook")
				.config(CrateSettings.SPEC)
				.permissions(CratePermissions.ALL)
				.onInitialize(ctx -> {
					config = ctx.config().orElseThrow();
					log = ctx.logger();
					CrateStore store = new CrateStore(MejtaCoreAPI.dataStore(ID), problem -> log.warn("MejtaCrates data: {}", problem));
					store.load();
					service = new CrateService(store, config, log);
					CrateCommands.register();
					MejtaCoreAPI.registerServerCommands(ID, (root, buildContext) -> root
							.then(Commands.literal("status")
									.requires(MejtaCoreAPI.requires(CratePermissions.ADMIN))
									.executes(c -> {
										c.getSource().sendSystemMessage(Component.literal("MejtaCrates: " + store.crates().size() + " crate(s), "
												+ store.blocks().size() + " crate block(s), " + store.players().size() + " player file(s); economy "
												+ (MejtaCoreAPI.economy().isAvailable() ? "by " + MejtaCoreAPI.economy().providerId().orElse("?") : "not installed (money rewards are not drawn)"))
												.withStyle(ChatFormatting.GOLD));
										return 1;
									})));
					CrateHooks.register();
					ServerLifecycleEvents.SERVER_STARTED.register(MejtaCrates::createExample);
					ServerLifecycleEvents.SERVER_STOPPING.register(server -> service.stopAll());
					ServerPlayerEvents.JOIN.register(player -> service.onJoin(player));
					log.info("MejtaCrates initialized with {} crate(s)", store.crates().size());
				})
				.build());
	}

	/** On the very first start, an example crate shows how crates look; delete it with /crates admin delete example. */
	private static void createExample(MinecraftServer server) {
		CrateStore store = service.store();
		if (!store.isEmpty() || store.raw().read("meta").map(m -> m.has("example_created")).orElse(false)) {
			return;
		}
		HolderLookup.Provider registries = server.registryAccess();
		Crate example = new Crate("example", "Example Crate", "GOLD", Optional.of(ItemJson.encode(registries, new ItemStack(Items.CHEST))),
				Optional.empty(), List.of(
						Reward.ofItem(40, ItemJson.encode(registries, new ItemStack(Items.IRON_INGOT, 16))),
						Reward.ofItem(25, ItemJson.encode(registries, new ItemStack(Items.DIAMOND, 3))),
						Reward.ofItem(15, ItemJson.encode(registries, new ItemStack(Items.GOLDEN_APPLE, 2))),
						Reward.ofMoney(15, 100L * pow10(MejtaCoreAPI.economy().fractionDigits())),
						Reward.ofItem(4, ItemJson.encode(registries, new ItemStack(Items.NETHERITE_INGOT))),
						Reward.ofItem(1, ItemJson.encode(registries, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE)))));
		store.save(example);
		JsonObject meta = new JsonObject();
		meta.addProperty("example_created", true);
		store.raw().write("meta", meta);
		log.info("Created the example crate 'example' (see /crates admin info example)");
	}

	private static long pow10(int digits) {
		long out = 1;
		for (int i = 0; i < digits; i++) {
			out *= 10;
		}
		return out;
	}

	public static ConfigHandle config() {
		return config;
	}

	public static Logger log() {
		return log;
	}

	public static CrateService service() {
		return service;
	}

	/** {@code null} before initialization or if MejtaCrates failed: hooks then change nothing. */
	public static CrateService serviceOrNull() {
		return service;
	}
}
