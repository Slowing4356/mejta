package com.mejta.mejtautils.inspect;

import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.menu.ServerMenus;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.util.UtilsAudit;

/**
 * {@code /status <player>}: health, hunger, XP, effects and game mode, refreshed live. Clearing effects
 * and changing the game mode need their own permission, a confirmation and are audited.
 *
 * <p>A server cannot add a permanent element to an unmodded client's screen (the top-right effect
 * icons are drawn by the client for its own player only), so this is a menu; see the README.
 */
public final class StatusMenu {
	private static final GameType[] MODES = {GameType.SURVIVAL, GameType.CREATIVE, GameType.ADVENTURE, GameType.SPECTATOR};

	private StatusMenu() {
	}

	/** Opens the panel; audited. */
	public static void openFor(ServerPlayer viewer, ServerPlayer target) {
		if (!allowed(viewer, target)) {
			viewer.sendSystemMessage(Component.literal("You may not inspect " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.RED));
			return;
		}
		UtilsAudit.record("status.open", viewer, target, AuditOutcome.SUCCESS, Map.of());
		show(viewer, target);
	}

	private static void show(ServerPlayer viewer, ServerPlayer target) {
		boolean mayEffects = MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.STATUS_EFFECTS);
		boolean mayMode = MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.STATUS_GAMEMODE);
		ServerMenu.builder(Component.literal("Status: " + target.getGameProfile().name()), 3)
				.icon(10, () -> PlayerInfo.head(target, true))
				.icon(11, () -> PlayerInfo.health(target))
				.icon(12, () -> PlayerInfo.food(target))
				.icon(13, () -> PlayerInfo.experience(target))
				.button(14, () -> PlayerInfo.effects(target, mayEffects
								? List.of(Component.empty(), MenuIcons.line("Shift-click: clear all effects"))
								: List.of()),
						click -> {
							if (click.type().isShift() && mayEffects) {
								confirmClearEffects(viewer, target);
							}
						})
				.button(15, () -> PlayerInfo.gameModeIcon(target, mayMode
								? List.of(Component.empty(), MenuIcons.line("Click: choose a game mode"))
								: List.of()),
						click -> {
							if (mayMode) {
								chooseGameMode(viewer, target);
							}
						})
				.button(16, () -> MenuIcons.of(Items.CHEST, Component.literal("Inventory").withStyle(ChatFormatting.YELLOW),
								MenuIcons.line("Click: /invsee")),
						click -> Inspect.openInventory(viewer, target, false))
				.refreshEvery(MejtaUtils.config().get(UtilsSettings.INVSEE_REFRESH_TICKS))
				.validWhile(p -> allowed(p, target))
				.open(viewer);
	}

	private static boolean allowed(ServerPlayer viewer, ServerPlayer target) {
		return !target.isRemoved() && !target.hasDisconnected()
				&& MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.STATUS_VIEW) && Targets.mayActOn(viewer, target);
	}

	private static void confirmClearEffects(ServerPlayer viewer, ServerPlayer target) {
		ServerMenus.confirm(viewer, Component.literal("Clear effects?"),
				List.of(MenuIcons.line("Removes all " + target.getActiveEffects().size() + " active effect(s) of " + target.getGameProfile().name())),
				() -> {
					// Re-checked: time passed since the question.
					if (!allowed(viewer, target) || !MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.STATUS_EFFECTS)) {
						UtilsAudit.record("status.clear_effects", viewer, target, AuditOutcome.DENIED, Map.of());
						return;
					}
					int count = target.getActiveEffects().size();
					target.removeAllEffects();
					UtilsAudit.record("status.clear_effects", viewer, target, AuditOutcome.SUCCESS, Map.of("effects", count));
					viewer.sendSystemMessage(Component.literal("Cleared " + count + " effect(s) of " + target.getGameProfile().name())
							.withStyle(ChatFormatting.GREEN));
					show(viewer, target);
				},
				() -> show(viewer, target));
	}

	private static void chooseGameMode(ServerPlayer viewer, ServerPlayer target) {
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Game mode of " + target.getGameProfile().name()), 1);
		for (int i = 0; i < MODES.length; i++) {
			GameType mode = MODES[i];
			menu.button(1 + i * 2, () -> MenuIcons.of(iconOf(mode),
					Component.literal(mode.getName()).withStyle(mode == PlayerInfo.gameMode(target) ? ChatFormatting.GREEN : ChatFormatting.WHITE)),
					click -> confirmGameMode(viewer, target, mode));
		}
		menu.validWhile(p -> allowed(p, target)).open(viewer);
	}

	private static net.minecraft.world.item.Item iconOf(GameType mode) {
		return switch (mode) {
			case SURVIVAL -> Items.IRON_SWORD;
			case CREATIVE -> Items.GRASS_BLOCK;
			case ADVENTURE -> Items.MAP;
			case SPECTATOR -> Items.ENDER_EYE;
		};
	}

	private static void confirmGameMode(ServerPlayer viewer, ServerPlayer target, GameType mode) {
		GameType from = PlayerInfo.gameMode(target);
		ServerMenus.confirm(viewer, Component.literal("Change game mode?"),
				List.of(MenuIcons.line(target.getGameProfile().name() + ": " + from.getName() + " -> " + mode.getName())),
				() -> {
					if (!allowed(viewer, target) || !MejtaCoreAPI.hasPermission(viewer, UtilsPermissions.STATUS_GAMEMODE)) {
						UtilsAudit.record("status.gamemode", viewer, target, AuditOutcome.DENIED, Map.of("to", mode.getName()));
						return;
					}
					if (MejtaUtils.staffMode().isActive(target.getUUID())) {
						viewer.sendSystemMessage(Component.literal(target.getGameProfile().name()
								+ " is in staff mode; their game mode is restored when it ends.").withStyle(ChatFormatting.RED));
						return;
					}
					boolean changed = target.setGameMode(mode);
					UtilsAudit.record("status.gamemode", viewer, target, changed ? AuditOutcome.SUCCESS : AuditOutcome.FAILED,
							Map.of("from", from.getName(), "to", mode.getName()));
					show(viewer, target);
				},
				() -> show(viewer, target));
	}
}
