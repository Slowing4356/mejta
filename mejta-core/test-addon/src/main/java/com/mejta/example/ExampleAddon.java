package com.mejta.example;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.AddonRegistrar;
import com.mejta.mejtacore.api.MejtaAddon;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.presence.VisibilityProvider;

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

	// API 4 test hooks, read by the client gametest (same JVM as the test server).
	public static final AtomicInteger MENU_CLICKS = new AtomicInteger();
	public static final AtomicInteger MENU_CLOSES = new AtomicInteger();
	public static volatile MenuClick.Type lastClickType;
	public static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();

	public static ConfigHandle config() {
		return config;
	}

	/** Demonstrates the API 4 additions: top-level commands, server menus, audit entries and presence. */
	private static void registerApi4Examples() {
		MejtaCoreAPI.registerRootCommand(ID, "examplecmd", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(PING))
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("example root command ok"), false);
					return 1;
				}));
		// Deliberately clashes with vanilla /list: MejtaCore keeps vanilla's and offers /mejtaadmin example list.
		MejtaCoreAPI.registerRootCommand(ID, "list", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(PING))
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("example list ok"), false);
					return 1;
				}));
		MejtaCoreAPI.registerServerCommands(ID, (root, buildContext) -> root
				.then(Commands.literal("menu")
						.requires(MejtaCoreAPI.requires(PING))
						.executes(c -> {
							ServerPlayer player = c.getSource().getPlayerOrException();
							ServerMenu.builder(Component.literal("Example menu"), 3)
									.button(13, () -> MenuIcons.of(Items.EMERALD, Component.literal("Clicks: " + MENU_CLICKS.get())),
											click -> {
												MENU_CLICKS.incrementAndGet();
												lastClickType = click.type();
												click.menu().refresh();
											})
									.icon(4, () -> MenuIcons.of(Items.PAPER, Component.literal("Just an icon")))
									.onClose(p -> MENU_CLOSES.incrementAndGet())
									.open(player);
							return 1;
						}))
				.then(Commands.literal("audit")
						.requires(MejtaCoreAPI.requires(PING))
						.executes(c -> {
							MejtaCoreAPI.audit().record(AuditEntry.builder(ID, "example.test")
									.actor(MejtaCoreAPI.actor(c.getSource()))
									.target("nothing")
									.outcome(AuditOutcome.SUCCESS)
									.detail("note", "from /mejtaadmin example audit")
									.build());
							c.getSource().sendSuccess(() -> Component.literal("audit recorded"), false);
							return 1;
						})));
		MejtaCoreAPI.presence().registerVisibilityProvider(ID, new VisibilityProvider() {
			@Override
			public boolean isHidden(UUID player) {
				return HIDDEN.contains(player);
			}
		});
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
					registerApi4Examples();
					config = ctx.config().orElseThrow();
					int launches = config.get(LAUNCH_COUNT) + 1;
					config.set(LAUNCH_COUNT, launches);
					config.save();
					ctx.logger().info("Example addon initialized (launch #{}, radius {})", launches, config.get(RADIUS));
				})
				.build());
	}
}
