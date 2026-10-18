package com.mejta.mejtautils.vanish;

import java.util.function.Consumer;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.UtilsAudit;

/** {@code /vanish [on|off]}, {@code /vanish settings}, {@code /vanish fakequit}, {@code /vanish fakejoin}. */
public final class VanishCommands {
	private VanishCommands() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "vanish", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.VANISH))
				.executes(ctx -> toggle(ctx, null))
				.then(Commands.literal("on").executes(ctx -> toggle(ctx, true)))
				.then(Commands.literal("off").executes(ctx -> toggle(ctx, false)))
				.then(Commands.literal("settings").executes(VanishCommands::settings))
				.then(Commands.literal("fakequit")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.VANISH_FAKE))
						.executes(ctx -> fake(ctx, true)))
				.then(Commands.literal("fakejoin")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.VANISH_FAKE))
						.executes(ctx -> fake(ctx, false))));
	}

	private static int toggle(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		VanishManager vanish = MejtaUtils.vanish();
		boolean target = on != null ? on : !vanish.isHidden(player.getUUID());
		if (!target && MejtaUtils.staffMode().isActive(player.getUUID())) {
			ctx.getSource().sendFailure(Component.literal("You are in staff mode; leave it with /staffmode off to become visible."));
			return 0;
		}
		vanish.setVanished(player, target, MejtaCoreAPI.actor(player));
		return 1;
	}

	private static int fake(CommandContext<CommandSourceStack> ctx, boolean quit) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String action = quit ? "vanish.fakequit" : "vanish.fakejoin";
		if (!MejtaUtils.config().get(UtilsSettings.VANISH_FAKE_PRESENCE)) {
			ctx.getSource().sendFailure(Component.literal("Fake quit/join is off (vanish.fake_presence.enabled)."));
			UtilsAudit.record(action, MejtaCoreAPI.actor(player), player.getUUID(), player.getGameProfile().name(), AuditOutcome.DENIED,
					java.util.Map.of("reason", "vanish.fake_presence.enabled is off"), null);
			return 0;
		}
		VanishState state = MejtaUtils.vanish().state(player.getUUID());
		if (quit && state.fakeOffline) {
			ctx.getSource().sendFailure(Component.literal("You already appear offline."));
			return 0;
		}
		if (!quit && !state.vanished) {
			ctx.getSource().sendFailure(Component.literal("You are visible already; /vanish fakejoin is for after /vanish fakequit."));
			return 0;
		}
		if (quit) {
			MejtaUtils.vanish().fakeQuit(player, MejtaCoreAPI.actor(player));
		} else {
			MejtaUtils.vanish().fakeJoin(player, MejtaCoreAPI.actor(player));
		}
		return 1;
	}

	private static int settings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		open(ctx.getSource().getPlayerOrException());
		return 1;
	}

	static void open(ServerPlayer player) {
		VanishManager vanish = MejtaUtils.vanish();
		VanishState s = vanish.state(player.getUUID());
		ServerMenu.builder(Component.literal("Vanish settings"), 3)
				.button(10, () -> toggleIcon(Items.HOPPER, "Item and XP pickup", s.itemPickup),
						flip(player, st -> st.itemPickup = !st.itemPickup))
				.button(11, () -> toggleIcon(Items.ZOMBIE_HEAD, "Mobs may target you", s.mobTargeting),
						flip(player, st -> st.mobTargeting = !st.mobTargeting))
				.button(12, () -> toggleIcon(Items.GOLDEN_CARROT, "Night vision", s.nightVision),
						flip(player, st -> st.nightVision = !st.nightVision))
				.button(13, () -> toggleIcon(Items.CHEST, "Silent containers", s.silentContainers),
						flip(player, st -> st.silentContainers = !st.silentContainers))
				.button(14, () -> MenuIcons.of(Items.FEATHER,
								Component.literal(String.format(java.util.Locale.ROOT, "Flight speed: x%.1f", s.flightSpeed)).withStyle(ChatFormatting.AQUA),
								MenuIcons.line("Left click: +0.5, right click: -0.5 (1 to 4)")),
						click -> {
							s.flightSpeed = Math.clamp(s.flightSpeed + (click.type().isRight() ? -0.5 : 0.5), 1.0, 4.0);
							vanish.settingsChanged(player);
							click.menu().refresh();
						})
				.button(16, () -> toggleIcon(vanish.isHidden(player.getUUID()) ? Items.ENDER_EYE : Items.ENDER_PEARL, "Vanished",
								vanish.isHidden(player.getUUID())),
						click -> {
							boolean to = !vanish.isHidden(player.getUUID());
							if (!to && MejtaUtils.staffMode().isActive(player.getUUID())) {
								player.sendSystemMessage(Component.literal("Leave staff mode to become visible.").withStyle(ChatFormatting.RED));
								return;
							}
							vanish.setVanished(player, to, MejtaCoreAPI.actor(player));
							click.menu().refresh();
						})
				.validWhile(p -> MejtaCoreAPI.hasPermission(p, UtilsPermissions.VANISH))
				.open(player);
	}

	private static ServerMenu.ClickHandler flip(ServerPlayer player, Consumer<VanishState> change) {
		return click -> {
			change.accept(MejtaUtils.vanish().state(player.getUUID()));
			MejtaUtils.vanish().settingsChanged(player);
			click.menu().refresh();
		};
	}

	private static net.minecraft.world.item.ItemStack toggleIcon(Item item, String name, boolean on) {
		return MenuIcons.of(item, Component.literal(name + ": " + (on ? "on" : "off")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED),
				MenuIcons.line("Click to switch"));
	}
}
