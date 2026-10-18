package com.mejta.example.client;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.example.ExampleAddon;
import com.mejta.mejtacore.api.client.MejtaClientAddon;
import com.mejta.mejtacore.api.client.MejtaCoreClientAPI;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;

/** Client side of the example addon: a menu entry, a sub-page and client commands. */
public final class ExampleAddonClient implements MejtaClientAddon {
	@Override
	public void initializeClient() {
		MejtaCoreClientAPI.registerMenuEntry(ExampleAddon.ID, MenuEntry.builder("example_status")
				.icon(() -> new ItemStack(Items.COMPASS))
				.title(Component.literal("Example Status").withStyle(ChatFormatting.AQUA))
				.dynamicDescription(() -> List.of(
						Component.literal("Radius: " + config().get(ExampleAddon.RADIUS)).withStyle(ChatFormatting.GRAY),
						Component.literal("Mode: " + config().get(ExampleAddon.MODE)).withStyle(ChatFormatting.GRAY),
						Component.literal("Click to print the greeting").withStyle(ChatFormatting.DARK_GRAY)))
				.onClick(ctx -> ctx.player().sendSystemMessage(Component.literal(config().get(ExampleAddon.GREETING))))
				.build());

		MejtaCoreClientAPI.registerMenuEntry(ExampleAddon.ID, MenuEntry.builder("example_page")
				.icon(() -> new ItemStack(Items.MAP))
				.title(Component.literal("Example Page").withStyle(ChatFormatting.AQUA))
				.description(Component.literal("Opens a page owned by the addon").withStyle(ChatFormatting.GRAY))
				.onClick(ctx -> ctx.openPage(Component.literal("Example Page"), () -> List.of(
						MenuEntry.builder("hello")
								.icon(() -> new ItemStack(Items.CAKE))
								.title(Component.literal("Say Hello"))
								.onClick(inner -> inner.player().sendSystemMessage(Component.literal("Hello!")))
								.build())))
				.build());

		MejtaCoreClientAPI.registerCommands(ExampleAddon.ID, root -> root
				.then(ClientCommands.literal("status").executes(ctx -> {
					ConfigHandle cfg = config();
					ctx.getSource().sendFeedback(Component.literal(cfg.get(ExampleAddon.GREETING)));
					ctx.getSource().sendFeedback(Component.literal("radius=" + cfg.get(ExampleAddon.RADIUS)
							+ " speed=" + cfg.get(ExampleAddon.SPEED) + " mode=" + cfg.get(ExampleAddon.MODE)
							+ " launches=" + cfg.get(ExampleAddon.LAUNCH_COUNT)));
					return 1;
				}))
				.then(ClientCommands.literal("radius")
						.then(ClientCommands.argument("value", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
							int value = IntegerArgumentType.getInteger(ctx, "value");
							config().set(ExampleAddon.RADIUS, value);
							config().save();
							ctx.getSource().sendFeedback(Component.literal("Radius set to " + value));
							return value;
						}))));
	}

	private static ConfigHandle config() {
		return ExampleAddon.config();
	}
}
