package com.mejta.mejtacore.commands;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.network.ServerConfigSync;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/**
 * Server-side command namespace {@code /mejtaadmin}. Kept separate from the client-only {@code /mejta}
 * so the two never shadow each other.
 */
public final class ServerCommands {
	public static final String ROOT = "mejtaadmin";

	private static final Map<String, List<ServerCommandProvider>> PROVIDERS = new TreeMap<>();

	private ServerCommands() {
	}

	public static void addProvider(String addonId, ServerCommandProvider provider) {
		if (AddonDefinition.RESERVED_IDS.contains(addonId)) {
			throw new IllegalArgumentException("'" + addonId + "' is reserved");
		}
		synchronized (PROVIDERS) {
			PROVIDERS.computeIfAbsent(addonId, k -> new CopyOnWriteArrayList<>()).add(provider);
		}
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
				.executes(ServerCommands::help)
				.then(Commands.literal("help").executes(ServerCommands::help))
				.then(Commands.literal("addons")
						.requires(MejtaCoreAPI.requires(PermissionRegistry.ADMIN))
						.executes(ServerCommands::addons))
				.then(Commands.literal("config")
						.requires(s -> MejtaCoreAPI.hasPermission(s, PermissionRegistry.ADMIN)
								|| MejtaCoreAPI.hasPermission(s, PermissionRegistry.CONFIG_EDIT))
						.then(Commands.literal("reload")
								.requires(MejtaCoreAPI.requires(PermissionRegistry.ADMIN))
								.executes(ServerCommands::reload))
						.then(Commands.literal("get")
								.requires(MejtaCoreAPI.requires(PermissionRegistry.CONFIG_EDIT))
								.then(Commands.argument("config", StringArgumentType.word()).suggests(CONFIG_IDS)
										.executes(ctx -> get(ctx, null))
										.then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS)
												.executes(ctx -> get(ctx, StringArgumentType.getString(ctx, "key"))))))
						.then(Commands.literal("set")
								.requires(MejtaCoreAPI.requires(PermissionRegistry.CONFIG_EDIT))
								.then(Commands.argument("config", StringArgumentType.word()).suggests(CONFIG_IDS)
										.then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS)
												.then(Commands.argument("value", StringArgumentType.greedyString())
														.executes(ctx -> edit(ctx, ConfigEditing.Op.SET))))))
						.then(Commands.literal("reset")
								.requires(MejtaCoreAPI.requires(PermissionRegistry.CONFIG_EDIT))
								.then(Commands.argument("config", StringArgumentType.word()).suggests(CONFIG_IDS)
										.then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS)
												.executes(ctx -> edit(ctx, ConfigEditing.Op.RESET))))));

		Map<String, List<ServerCommandProvider>> snapshot;
		synchronized (PROVIDERS) {
			snapshot = new TreeMap<>(PROVIDERS);
		}
		for (Map.Entry<String, List<ServerCommandProvider>> entry : snapshot.entrySet()) {
			String addonId = entry.getKey();
			if (!MejtaCoreAPI.isAddonActive(addonId)) {
				continue;
			}
			LiteralArgumentBuilder<CommandSourceStack> addonRoot = Commands.literal(addonId);
			for (ServerCommandProvider provider : entry.getValue()) {
				try {
					provider.register(addonRoot, buildContext);
				} catch (Throwable t) {
					Diagnostics.error(addonId, "Server command provider failed", t);
				}
			}
			root.then(addonRoot);
		}
		dispatcher.register(root);
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("MejtaCore server commands:").withStyle(ChatFormatting.GOLD));
		line(source, "/" + ROOT + " help", "this list");
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.ADMIN)) {
			line(source, "/" + ROOT + " addons", "registered addons and their state");
			line(source, "/" + ROOT + " config reload", "re-read every config file from disk");
		}
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.CONFIG_EDIT)) {
			line(source, "/" + ROOT + " config get <config> [key]", "show settings");
			line(source, "/" + ROOT + " config set <config> <key> <value>", "change a setting (saved immediately)");
			line(source, "/" + ROOT + " config reset <config> <key>", "restore a setting's default");
		}
		synchronized (PROVIDERS) {
			for (String addonId : PROVIDERS.keySet()) {
				if (MejtaCoreAPI.isAddonActive(addonId)) {
					line(source, "/" + ROOT + " " + addonId + " ...", "commands of addon '" + addonId + "'");
				}
			}
		}
		return 1;
	}

	private static int addons(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("MejtaCore " + MejtaCoreAPI.coreVersion() + " addons:").withStyle(ChatFormatting.GOLD));
		for (AddonInfo addon : MejtaCoreAPI.getAddons()) {
			Component status = addon.isActive()
					? Component.literal("active").withStyle(ChatFormatting.GREEN)
					: Component.literal(addon.state().name().toLowerCase() + addon.failureReason().map(r -> ": " + r).orElse(""))
							.withStyle(ChatFormatting.RED);
			source.sendSystemMessage(Component.literal(" " + addon.name() + " " + addon.version() + " (" + addon.id() + ") ")
					.withStyle(ChatFormatting.WHITE).append(status));
		}
		return MejtaCoreAPI.getAddons().size();
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		int problemsBefore = Diagnostics.entries().size();
		int count = MejtaCore.configs().reloadAll();
		int newProblems = Diagnostics.entries().size() - problemsBefore;
		ctx.getSource().sendSuccess(() -> Component.literal("Reloaded " + count + " configuration file(s)."), true);
		if (newProblems > 0) {
			ctx.getSource().sendFailure(Component.literal(newProblems
					+ " problem(s) found while reloading; invalid values were reset to defaults. See the server log."));
		}
		return count;
	}

	private static final SuggestionProvider<CommandSourceStack> CONFIG_IDS = (ctx, builder) -> {
		for (ConfigHandle handle : ServerConfigSync.editableBy(ctx.getSource().permissions())) {
			builder.suggest(handle.id());
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> SETTING_KEYS = (ctx, builder) -> {
		String configId = StringArgumentType.getString(ctx, "config");
		ServerConfigSync.editable(ctx.getSource().permissions(), configId)
				.ifPresent(handle -> handle.spec().settings().forEach(s -> builder.suggest(s.key())));
		return builder.buildFuture();
	};

	private static int get(CommandContext<CommandSourceStack> ctx, String key) {
		CommandSourceStack source = ctx.getSource();
		String configId = StringArgumentType.getString(ctx, "config");
		ConfigHandle handle = ServerConfigSync.editable(source.permissions(), configId).orElse(null);
		if (handle == null) {
			source.sendFailure(Component.literal("You may not view config '" + configId + "'"));
			return 0;
		}
		int shown = 0;
		source.sendSystemMessage(Component.literal(configId + ":").withStyle(ChatFormatting.GOLD));
		for (Setting<?> setting : handle.spec().settings()) {
			if (key != null && !setting.key().equals(key)) {
				continue;
			}
			source.sendSystemMessage(Component.literal(" " + setting.key() + " = ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(SettingText.format(setting, handle.get(setting))).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  (default " + SettingText.format(setting, setting.defaultValue()) + ")")
							.withStyle(ChatFormatting.DARK_GRAY)));
			shown++;
		}
		if (shown == 0) {
			source.sendFailure(Component.literal("Unknown setting '" + key + "' in " + configId));
		}
		return shown;
	}

	private static int edit(CommandContext<CommandSourceStack> ctx, ConfigEditing.Op op) {
		CommandSourceStack source = ctx.getSource();
		String value = op == ConfigEditing.Op.SET ? StringArgumentType.getString(ctx, "value") : "";
		ConfigEditing.Result result = ServerConfigSync.apply(source, StringArgumentType.getString(ctx, "config"), op.name(),
				StringArgumentType.getString(ctx, "key"), value);
		if (result.ok()) {
			source.sendSuccess(() -> Component.literal(result.message()), true);
			return 1;
		}
		source.sendFailure(Component.literal(result.message()));
		return 0;
	}

	private static void line(CommandSourceStack source, String command, String description) {
		source.sendSystemMessage(Component.literal(" " + command).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)));
	}
}
