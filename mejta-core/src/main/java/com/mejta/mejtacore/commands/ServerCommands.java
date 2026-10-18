package com.mejta.mejtacore.commands;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
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
						.requires(MejtaCoreAPI.requires(PermissionRegistry.ADMIN))
						.then(Commands.literal("reload").executes(ServerCommands::reload)));

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

	private static void line(CommandSourceStack source, String command, String description) {
		source.sendSystemMessage(Component.literal(" " + command).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)));
	}
}
