package com.mejta.mejtacore.api.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Adds client-side subcommands under an addon's namespace.
 *
 * <p>MejtaCore passes a {@code literal(<addon id>)} node; everything attached to it becomes
 * available as both {@code /mejtacore <addon id> ...} and {@code /mejta <addon id> ...}:
 *
 * <pre>{@code
 * MejtaCoreClientAPI.registerCommands("example", root -> root
 *     .then(ClientCommands.literal("status").executes(ctx -> {
 *         ctx.getSource().sendFeedback(Component.literal("All good"));
 *         return 1;
 *     })));
 * }</pre>
 *
 * The provider is called again each time the command tree is rebuilt (on joining a world), so it
 * must not keep state between calls.
 */
@FunctionalInterface
public interface ClientCommandProvider {
	void register(LiteralArgumentBuilder<FabricClientCommandSource> addonRoot);
}
