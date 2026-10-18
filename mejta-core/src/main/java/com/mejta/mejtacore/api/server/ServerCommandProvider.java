package com.mejta.mejtacore.api.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;

/**
 * Adds server-side subcommands under {@code /mejtaadmin <addon id>}. They run on the server (dedicated
 * or integrated) and work for players without any mod installed.
 *
 * <pre>{@code
 * MejtaCoreAPI.registerServerCommands("myaddon", (root, buildContext) -> root
 *     .then(Commands.literal("status")
 *         .requires(MejtaCoreAPI.requires(MyAddon.ADMIN))
 *         .executes(ctx -> {
 *             ctx.getSource().sendSuccess(() -> Component.literal("All good"), false);
 *             return 1;
 *         })));
 * }</pre>
 *
 * Gate each subcommand with {@code MejtaCoreAPI.requires(permission)}; the addon root itself is
 * visible to anyone. The provider is called each time the server builds its command tree, so it
 * must not keep state between calls.
 */
@FunctionalInterface
public interface ServerCommandProvider {
	void register(LiteralArgumentBuilder<CommandSourceStack> addonRoot, CommandBuildContext buildContext);
}
