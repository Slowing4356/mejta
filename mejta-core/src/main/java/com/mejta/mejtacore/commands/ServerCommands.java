package com.mejta.mejtacore.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtacore.audit.AuditTrail;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.menu.ConfigServerMenu;
import com.mejta.mejtacore.network.ServerConfigSync;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/**
 * Server-side command namespace {@code /mejtaadmin}. Kept separate from the client-only {@code /mejta}
 * so the two never shadow each other.
 */
public final class ServerCommands {
	public static final String ROOT = "mejtaadmin";

	private static final Map<String, List<ServerCommandProvider>> PROVIDERS = new TreeMap<>();

	/** Top-level command names an addon may claim: lowercase, max 32 characters. */
	public static final Pattern ROOT_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
	private static final Set<String> RESERVED_ROOTS = Set.of(ROOT, "mejta", "mejtacore");

	private record RootCommand(String addonId, String name, ServerCommandProvider provider) {
	}

	private static final List<RootCommand> ROOTS = new CopyOnWriteArrayList<>();
	/** Names already reported as unavailable, so a command-tree rebuild (/reload) doesn't repeat the warning. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

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

	/** @see com.mejta.mejtacore.api.MejtaCoreAPI#registerRootCommand */
	public static void addRootCommand(String addonId, String name, ServerCommandProvider provider) {
		Objects.requireNonNull(provider, "provider");
		if (AddonDefinition.RESERVED_IDS.contains(addonId)) {
			throw new IllegalArgumentException("'" + addonId + "' is reserved");
		}
		if (name == null || !ROOT_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid command name '" + name + "': must match " + ROOT_NAME.pattern());
		}
		if (RESERVED_ROOTS.contains(name)) {
			throw new IllegalArgumentException("Command name '" + name + "' is reserved by MejtaCore");
		}
		ROOTS.add(new RootCommand(addonId, name, provider));
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
				.executes(ServerCommands::help)
				.then(Commands.literal("help").executes(ServerCommands::help))
				.then(Commands.literal("addons")
						.requires(MejtaCoreAPI.requires(PermissionRegistry.ADMIN))
						.executes(ServerCommands::addons))
				.then(Commands.literal("audit")
						.requires(MejtaCoreAPI.requires(PermissionRegistry.ADMIN))
						.then(Commands.literal("verify").executes(ServerCommands::auditVerify))
						.then(Commands.literal("recent")
								.executes(ctx -> auditRecent(ctx, 10))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
										.executes(ctx -> auditRecent(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("config")
						.requires(s -> MejtaCoreAPI.hasPermission(s, PermissionRegistry.ADMIN)
								|| MejtaCoreAPI.hasPermission(s, PermissionRegistry.CONFIG_EDIT))
						.executes(ServerCommands::configRoot)
						.then(Commands.literal("menu")
								.requires(MejtaCoreAPI.requires(PermissionRegistry.CONFIG_EDIT))
								.executes(ctx -> openMenu(ctx, null))
								.then(Commands.argument("config", StringArgumentType.word()).suggests(CONFIG_IDS)
										.executes(ctx -> openMenu(ctx, StringArgumentType.getString(ctx, "config")))))
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
		for (RootCommand command : ROOTS) {
			snapshot.computeIfAbsent(command.addonId(), k -> new ArrayList<>());
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
			// Every top-level command is also reachable as /mejtaadmin <addon> <name>, in case its name is taken.
			for (RootCommand command : ROOTS) {
				if (command.addonId().equals(addonId)) {
					build(command, buildContext).ifPresent(addonRoot::then);
				}
			}
			root.then(addonRoot);
		}
		dispatcher.register(root);
		registerRootCommands(dispatcher, buildContext);
	}

	private static void registerRootCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		Set<String> disabled = disabledRootCommands();
		Set<String> claimed = new HashSet<>();
		for (RootCommand command : ROOTS) {
			String name = command.name();
			if (!MejtaCoreAPI.isAddonActive(command.addonId())) {
				continue;
			}
			String fallback = "/" + ROOT + " " + command.addonId() + " " + name;
			if (disabled.contains(name)) {
				report(name, null, "Command /" + name + " of addon '" + command.addonId() + "' is disabled in mejtacore.json ("
						+ CoreSettings.DISABLED_COMMANDS.key() + "); use " + fallback);
				continue;
			}
			if (!claimed.add(name) || dispatcher.getRoot().getChild(name) != null) {
				report(name, command.addonId(), "Command /" + name + " of addon '" + command.addonId()
						+ "' is not registered: the name is already used by Minecraft, another mod or addon. Use " + fallback
						+ ", or disable the other command.");
				continue;
			}
			build(command, buildContext).ifPresent(dispatcher::register);
		}
	}

	private static Optional<LiteralArgumentBuilder<CommandSourceStack>> build(RootCommand command, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(command.name());
		try {
			command.provider().register(literal, buildContext);
			return Optional.of(literal);
		} catch (Throwable t) {
			Diagnostics.error(command.addonId(), "Command /" + command.name() + " failed to build", t);
			return Optional.empty();
		}
	}

	private static void report(String name, String addonId, String message) {
		if (REPORTED.add(name + "|" + addonId)) {
			if (addonId == null) {
				MejtaLog.CORE.info(message);
			} else {
				Diagnostics.warn(addonId, message);
			}
		}
	}

	private static Set<String> disabledRootCommands() {
		Set<String> out = new HashSet<>();
		for (String part : MejtaCore.coreConfig().get(CoreSettings.DISABLED_COMMANDS).split(",")) {
			String name = part.trim().toLowerCase(java.util.Locale.ROOT);
			if (name.startsWith("/")) {
				name = name.substring(1);
			}
			if (!name.isEmpty()) {
				out.add(name);
			}
		}
		return out;
	}

	/** Top-level commands of active addons, as {@code name -> addon id}, for help texts. */
	public static Map<String, String> rootCommands() {
		Map<String, String> out = new TreeMap<>();
		for (RootCommand command : ROOTS) {
			if (MejtaCoreAPI.isAddonActive(command.addonId())) {
				out.putIfAbsent(command.name(), command.addonId());
			}
		}
		return out;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("MejtaCore server commands:").withStyle(ChatFormatting.GOLD));
		line(source, "/" + ROOT + " help", "this list");
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.ADMIN)) {
			line(source, "/" + ROOT + " addons", "registered addons and their state");
			line(source, "/" + ROOT + " config reload", "re-read every config file from disk");
			line(source, "/" + ROOT + " audit recent [count]", "latest entries of the shared audit log");
			line(source, "/" + ROOT + " audit verify", "check the audit log's hash chain for edits");
		}
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.CONFIG_EDIT)) {
			line(source, "/" + ROOT + " config menu [config]", "edit settings in a chest menu (no client mod needed)");
			line(source, "/" + ROOT + " config get <config> [key]", "show settings");
			line(source, "/" + ROOT + " config set <config> <key> <value>", "change a setting (saved immediately)");
			if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.CONFIG_SECRETS)) {
				line(source, "  secret settings (tokens, webhooks)", "set them from the server console if you can");
			}
			line(source, "/" + ROOT + " config reset <config> <key>", "restore a setting's default");
		}
		synchronized (PROVIDERS) {
			for (String addonId : PROVIDERS.keySet()) {
				if (MejtaCoreAPI.isAddonActive(addonId)) {
					line(source, "/" + ROOT + " " + addonId + " ...", "commands of addon '" + addonId + "'");
				}
			}
		}
		for (Map.Entry<String, String> command : rootCommands().entrySet()) {
			line(source, "/" + command.getKey() + " ...", "command of addon '" + command.getValue() + "'");
		}
		return 1;
	}

	/** {@code /mejtaadmin config}: the menu for players who may edit settings, otherwise the config commands. */
	private static int configRoot(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player != null && MejtaCoreAPI.hasPermission(source, PermissionRegistry.CONFIG_EDIT)) {
			return ConfigServerMenu.open(player) ? 1 : 0;
		}
		source.sendSystemMessage(Component.literal("Config commands:").withStyle(ChatFormatting.GOLD));
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.ADMIN)) {
			line(source, "/" + ROOT + " config reload", "re-read every config file from disk");
		}
		if (MejtaCoreAPI.hasPermission(source, PermissionRegistry.CONFIG_EDIT)) {
			line(source, "/" + ROOT + " config menu [config]", "edit settings in a chest menu (players only)");
			line(source, "/" + ROOT + " config get <config> [key]", "show settings");
			line(source, "/" + ROOT + " config set <config> <key> <value>", "change a setting (saved immediately)");
			line(source, "/" + ROOT + " config reset <config> <key>", "restore a setting's default");
		}
		return 1;
	}

	private static int openMenu(CommandContext<CommandSourceStack> ctx, String configId) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only players can open the menu; use /" + ROOT + " config get/set here"));
			return 0;
		}
		return (configId == null ? ConfigServerMenu.open(player) : ConfigServerMenu.open(player, configId)) ? 1 : 0;
	}

	private static int auditVerify(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("Checking the audit log...").withStyle(ChatFormatting.GRAY));
		AuditTrail.get().verify().thenCombine(AuditTrail.get().startsAtGenesis(), (result, genesis) -> {
			source.getServer().execute(() -> {
				if (result.intact()) {
					source.sendSystemMessage(Component.literal("Audit log intact: " + result.verified() + " entries"
							+ (genesis ? ", starting with the first entry ever written." : "; older files were rotated out."))
							.withStyle(ChatFormatting.GREEN));
				} else {
					source.sendFailure(Component.literal("Audit log NOT intact after " + result.verified() + " of " + result.records()
							+ " entries: " + result.problem()));
				}
			});
			return null;
		}).exceptionally(t -> {
			source.getServer().execute(() -> source.sendFailure(Component.literal("Could not read the audit log: " + t)));
			return null;
		});
		return 1;
	}

	private static int auditRecent(CommandContext<CommandSourceStack> ctx, int count) {
		CommandSourceStack source = ctx.getSource();
		AuditTrail.get().recent(count).thenAccept(lines -> source.getServer().execute(() -> {
			source.sendSystemMessage(Component.literal("Last " + lines.size() + " audit entries (newest first):").withStyle(ChatFormatting.GOLD));
			for (JsonObject line : lines) {
				String target = text(line, "target");
				source.sendSystemMessage(Component.literal(" " + text(line, "ts") + " ").withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal(text(line, "actor") + " " + text(line, "source") + ":" + text(line, "action")
								+ (target.isEmpty() ? "" : " " + target) + " ").withStyle(ChatFormatting.WHITE))
						.append(Component.literal(text(line, "outcome")).withStyle(
								"SUCCESS".equals(text(line, "outcome")) ? ChatFormatting.GREEN : ChatFormatting.RED)));
			}
		}));
		return 1;
	}

	private static String text(JsonObject line, String key) {
		return line.has(key) && line.get(key).isJsonPrimitive() ? line.get(key).getAsString() : "";
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
		long problemsBefore = Diagnostics.total();
		int count = MejtaCore.configs().reloadAll();
		long newProblems = Diagnostics.total() - problemsBefore;
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
			MutableComponent line = Component.literal(" " + setting.key() + " = ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(SettingText.display(setting, handle.get(setting))).withStyle(ChatFormatting.WHITE));
			if (SettingText.isSensitive(setting)) {
				line.append(Component.literal("  (secret)").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				line.append(Component.literal("  (default " + SettingText.format(setting, setting.defaultValue()) + ")")
						.withStyle(ChatFormatting.DARK_GRAY));
			}
			if (setting.isExperimental()) {
				line.append(Component.literal(" [experimental]").withStyle(ChatFormatting.LIGHT_PURPLE));
			}
			source.sendSystemMessage(line);
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
		String configId = StringArgumentType.getString(ctx, "config");
		String key = StringArgumentType.getString(ctx, "key");
		ConfigEditing.Result result = ServerConfigSync.apply(source, configId, op.name(), key, value);
		if (result.ok()) {
			// The message never holds a secret value, but changes to secrets are not announced to other operators either.
			source.sendSuccess(() -> Component.literal(result.message()), !ServerConfigSync.isSensitive(configId, key));
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
