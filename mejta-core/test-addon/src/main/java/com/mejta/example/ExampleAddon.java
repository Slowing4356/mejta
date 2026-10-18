package com.mejta.example;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * Registers the "example" addon through the {@code "mejtacore"} entrypoint (see fabric.mod.json).
 * This is the recommended way: it works no matter in which order Fabric loads the mods.
 */
public final class ExampleAddon implements MejtaAddon {
	public static final String ID = "example";

	public enum Mode {
		NORMAL,
		FAST,
		SILENT
	}

	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true)
			.describedAs("Master switch for the example addon");
	public static final Setting<Integer> RADIUS = Setting.integer("radius", 8, 1, 64)
			.describedAs("Example radius in blocks");
	public static final Setting<Double> SPEED = Setting.decimal("speed", 1.0, 0.1, 5.0)
			.describedAs("Example speed multiplier");
	public static final Setting<String> GREETING = Setting.string("greeting", "Hello from the Example Addon!")
			.validatedBy(text -> !text.isBlank() && text.length() <= 64)
			.describedAs("Message shown by /mejtacore example status");
	public static final Setting<Mode> MODE = Setting.enumeration("mode", Mode.NORMAL)
			.describedAs("Example mode");
	/** A secret (API 4): stored in config/mejta/secrets/example.json and never shown or sent to clients. */
	public static final Setting<String> API_TOKEN = Setting.string("api_token", "").secret()
			.describedAs("Example secret, e.g. a bot token");
	public static final Setting<Boolean> PREVIEW = Setting.bool("preview_feature", false).experimental()
			.describedAs("Example experimental setting (API 4)");
	public static final Setting<Integer> LAUNCH_COUNT = Setting.integer("launch_count", 0, 0, Integer.MAX_VALUE)
			.named("Launch Count")
			.describedAs("How many times the game started with this addon");

	/**
	 * Version 2 renamed "range" to "radius". Files written by version 1 are migrated automatically;
	 * settings that are missing afterwards get their defaults.
	 */
	public static final ConfigSpec CONFIG = ConfigSpec.builder(2)
			.add(ENABLED, RADIUS, SPEED, GREETING, MODE, API_TOKEN, PREVIEW, LAUNCH_COUNT)
			.migration(1, data -> data.rename("range", "radius"))
			.build();

	/** Everyone may use {@code /mejtaadmin example ping} and see example notices in chat. */
	public static final MejtaPermission PING = MejtaPermission.of("example.ping", PermissionLevel.ALL)
			.describedAs("Use /mejtaadmin example ping and see example notices");
	/** Only server owners may use {@code /mejtaadmin example secret}. */
	public static final MejtaPermission SECRET = MejtaPermission.of("example.secret", PermissionLevel.OWNERS)
			.describedAs("Use /mejtaadmin example secret");

	private static volatile ConfigHandle config;

	public static ConfigHandle config() {
		return config;
	}

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder(ID)
				.name("Example Addon")
				.version("1.0.0")
				.description("Demonstrates the MejtaCore API")
				.icon("minecraft:diamond")
				.config(CONFIG)
				.permissions(PING, SECRET)
				.onInitialize(ctx -> {
					MejtaCoreAPI.registerServerCommands(ID, (root, buildContext) -> root
							.then(Commands.literal("ping")
									.requires(MejtaCoreAPI.requires(PING))
									.executes(c -> {
										c.getSource().sendSuccess(() -> Component.literal("pong from server"), false);
										return 1;
									}))
							.then(Commands.literal("secret")
									.requires(MejtaCoreAPI.requires(SECRET))
									.executes(c -> {
										c.getSource().sendSuccess(() -> Component.literal("secret ok"), false);
										return 1;
									})));
					config = ctx.config().orElseThrow();
					int launches = config.get(LAUNCH_COUNT) + 1;
					config.set(LAUNCH_COUNT, launches);
					config.save();
					ctx.logger().info("Example addon initialized (launch #{}, radius {})", launches, config.get(RADIUS));
				})
				.build());
	}
}
