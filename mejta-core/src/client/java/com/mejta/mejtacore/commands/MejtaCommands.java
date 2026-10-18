package com.mejta.mejtacore.commands;

import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.mejta.mejtacore.addons.ClientAddonExtensions;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.client.ClientCommandProvider;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.ui.CoreMenus;
import com.mejta.mejtacore.ui.ScreenOpener;

/**
 * Client-side command tree. Registered identically under {@code /mejtacore} and {@code /mejta}.
 * Client commands run locally, need no operator permission and work on any server.
 */
public final class MejtaCommands {
	public static final List<String> ROOTS = List.of("mejtacore", "mejta");

	private MejtaCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		Map<String, List<ClientCommandProvider>> addonCommands = ClientAddonExtensions.activeCommandProviders();
		for (String root : ROOTS) {
			dispatcher.register(buildTree(root, addonCommands));
		}
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> buildTree(String root,
			Map<String, List<ClientCommandProvider>> addonCommands) {
		LiteralArgumentBuilder<FabricClientCommandSource> tree = ClientCommands.literal(root)
				.executes(MejtaCommands::openMenu)
				.then(ClientCommands.literal("gui").executes(MejtaCommands::openMenu))
				.then(ClientCommands.literal("help").executes(ctx -> help(ctx, root)))
				.then(ClientCommands.literal("addons").executes(MejtaCommands::addons))
				.then(ClientCommands.literal("about").executes(MejtaCommands::about))
				.then(ClientCommands.literal("config")
						.executes(ctx -> help(ctx, root))
						.then(ClientCommands.literal("reload").executes(MejtaCommands::reloadConfig)));

		addonCommands.forEach((addonId, providers) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> addonRoot = ClientCommands.literal(addonId);
			for (ClientCommandProvider provider : providers) {
				try {
					provider.register(addonRoot);
				} catch (RuntimeException e) {
					// Only report once, not for both roots.
					if (root.equals(ROOTS.getFirst())) {
						Diagnostics.error(addonId, "Command registration failed", e);
					}
				}
			}
			tree.then(addonRoot);
		});
		return tree;
	}

	private static int openMenu(CommandContext<FabricClientCommandSource> ctx) {
		ScreenOpener.open(CoreMenus::mainMenu);
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> ctx, String root) {
		FabricClientCommandSource source = ctx.getSource();
		source.sendFeedback(header("Commands (/" + root + " or /" + other(root) + ")"));
		line(source, "/" + root, "open the MejtaCore menu");
		line(source, "/" + root + " gui", "open the MejtaCore menu");
		line(source, "/" + root + " help", "show this list");
		line(source, "/" + root + " addons", "list registered addons");
		line(source, "/" + root + " config reload", "reload all configuration files");
		line(source, "/" + root + " about", "version and framework information");

		CommandNode<FabricClientCommandSource> rootNode = ctx.getRootNode().getChild(root);
		if (rootNode != null) {
			for (String addonId : ClientAddonExtensions.activeCommandProviders().keySet()) {
				CommandNode<FabricClientCommandSource> addonNode = rootNode.getChild(addonId);
				if (addonNode == null) {
					continue;
				}
				Map<CommandNode<FabricClientCommandSource>, String> usage = dispatcherUsage(ctx, addonNode);
				String name = MejtaCoreAPI.getAddon(addonId).map(AddonInfo::name).orElse(addonId);
				if (usage.isEmpty()) {
					line(source, "/" + root + " " + addonId, "commands of " + name);
				}
				usage.values().forEach(u -> line(source, "/" + root + " " + addonId + " " + u, name));
			}
		}
		return 1;
	}

	private static Map<CommandNode<FabricClientCommandSource>, String> dispatcherUsage(
			CommandContext<FabricClientCommandSource> ctx, CommandNode<FabricClientCommandSource> node) {
		CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommands.getActiveDispatcher();
		return dispatcher == null ? Map.of() : dispatcher.getSmartUsage(node, ctx.getSource());
	}

	private static int addons(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		var addons = MejtaCoreAPI.getAddons();
		source.sendFeedback(header("Registered addons (" + addons.size() + ")"));
		if (addons.isEmpty()) {
			source.sendFeedback(Component.literal("  No addons installed.").withStyle(ChatFormatting.GRAY));
		}
		for (AddonInfo addon : addons) {
			MutableComponent text = Component.literal("  " + addon.name()).withStyle(addon.isActive() ? ChatFormatting.GREEN : ChatFormatting.RED)
					.append(Component.literal(" " + addon.version() + " (" + addon.id() + ")").withStyle(ChatFormatting.GRAY));
			if (!addon.isActive()) {
				text.append(Component.literal(" - " + addon.failureReason().orElse(addon.state().name())).withStyle(ChatFormatting.RED));
			}
			source.sendFeedback(text);
		}
		long problems = Diagnostics.entries().stream().filter(e -> e.level() == Diagnostics.Level.ERROR).count();
		if (problems > 0) {
			source.sendFeedback(Component.literal("  " + problems + " error(s) recorded, see Installed Addons > Problems in the menu or the log.")
					.withStyle(ChatFormatting.YELLOW));
		}
		return addons.size();
	}

	private static int about(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		source.sendFeedback(header("MejtaCore " + MejtaCore.version()));
		line(source, "Public API version", String.valueOf(MejtaCoreAPI.API_VERSION));
		long active = MejtaCoreAPI.getAddons().stream().filter(AddonInfo::isActive).count();
		line(source, "Addons", active + " active / " + MejtaCoreAPI.getAddons().size() + " registered");
		line(source, "Config folder", "config/mejta/");
		return 1;
	}

	private static int reloadConfig(CommandContext<FabricClientCommandSource> ctx) {
		int before = Diagnostics.entries().size();
		int count = MejtaCore.configs().reloadAll();
		int newProblems = Diagnostics.entries().size() - before;
		ctx.getSource().sendFeedback(Component.literal("[MejtaCore] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Reloaded " + count + " configuration file(s).").withStyle(ChatFormatting.GREEN)));
		if (newProblems > 0) {
			ctx.getSource().sendError(Component.literal(newProblems + " problem(s) found while reloading; invalid values were reset to defaults. See the log."));
		}
		return count;
	}

	private static String other(String root) {
		return root.equals("mejta") ? "mejtacore" : "mejta";
	}

	private static Component header(String text) {
		return Component.literal("[MejtaCore] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	private static void line(FabricClientCommandSource source, String left, String right) {
		source.sendFeedback(Component.literal("  " + left).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" - " + right).withStyle(ChatFormatting.GRAY)));
	}
}
