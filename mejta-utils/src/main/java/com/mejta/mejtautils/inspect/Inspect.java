package com.mejta.mejtautils.inspect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsPermissions;
import com.mejta.mejtautils.UtilsSettings;
import com.mejta.mejtautils.util.Targets;
import com.mejta.mejtautils.util.Targets.Target;
import com.mejta.mejtautils.util.UtilsAudit;

/** {@code /invsee}, {@code /ecsee}, {@code /status}: registered through MejtaCore. */
public final class Inspect {
	private Inspect() {
	}

	public static void registerCommands() {
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "invsee", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.INVSEE_VIEW))
				.then(Commands.literal("claims")
						.requires(MejtaCoreAPI.requires(UtilsPermissions.INVSEE_OFFLINE))
						.executes(ctx -> {
							ClaimsMenu.open(ctx.getSource().getPlayerOrException());
							return 1;
						}))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> inventory(ctx, false))
						.then(Commands.literal("view").executes(ctx -> inventory(ctx, false)))
						.then(Commands.literal("edit")
								.requires(s -> MejtaCoreAPI.hasPermission(s, UtilsPermissions.INVSEE_EDIT)
										|| MejtaCoreAPI.hasPermission(s, UtilsPermissions.INVSEE_OFFLINE_EDIT))
								.executes(ctx -> inventory(ctx, true)))));
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "ecsee", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.ECSEE_VIEW))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> enderChest(ctx, false))
						.then(Commands.literal("view").executes(ctx -> enderChest(ctx, false)))
						.then(Commands.literal("edit")
								.requires(s -> MejtaCoreAPI.hasPermission(s, UtilsPermissions.ECSEE_EDIT)
										|| MejtaCoreAPI.hasPermission(s, UtilsPermissions.INVSEE_OFFLINE_EDIT))
								.executes(ctx -> enderChest(ctx, true)))));
		MejtaCoreAPI.registerRootCommand(MejtaUtils.ID, "status", (root, buildContext) -> root
				.requires(MejtaCoreAPI.requires(UtilsPermissions.STATUS_VIEW))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(Inspect::status)));
	}

	private static int inventory(CommandContext<CommandSourceStack> ctx, boolean edit) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer viewer = source.getPlayerOrException();
		Target target = Targets.single(ctx, "player");
		if (!checkTarget(source, target, "invsee.open", edit)) {
			return 0;
		}
		if (target.online().isPresent()) {
			ServerPlayer online = target.online().get();
			if (edit && !require(source, target, UtilsPermissions.INVSEE_EDIT, "invsee.open")) {
				return 0;
			}
			if (edit && MejtaUtils.staffMode().isActive(online.getUUID())) {
				source.sendFailure(Component.literal(target.name() + " is in staff mode: their survival inventory is saved and cannot be edited."));
				return 0;
			}
			if (edit && online == viewer) {
				// The menu shows the viewer's own inventory below the target's: the same slots twice.
				source.sendFailure(Component.literal("Your own inventory can only be viewed here; opening read-only."));
				edit = false;
			}
			openInventory(viewer, online, edit);
			return 1;
		}
		return openOffline(source, viewer, target, OfflineMenu.View.INVENTORY, edit);
	}

	private static int enderChest(CommandContext<CommandSourceStack> ctx, boolean edit) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer viewer = source.getPlayerOrException();
		Target target = Targets.single(ctx, "player");
		if (!checkTarget(source, target, "ecsee.open", edit)) {
			return 0;
		}
		if (target.online().isPresent()) {
			if (edit && !require(source, target, UtilsPermissions.ECSEE_EDIT, "ecsee.open")) {
				return 0;
			}
			if (edit && MejtaUtils.staffMode().isActive(target.id())) {
				source.sendFailure(Component.literal(target.name() + " is in staff mode; their ender chest can only be viewed until it ends."));
				return 0;
			}
			openEnderChest(viewer, target.online().get(), edit);
			return 1;
		}
		return openOffline(source, viewer, target, OfflineMenu.View.ENDER_CHEST, edit);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer viewer = source.getPlayerOrException();
		Target target = Targets.single(ctx, "player");
		if (!checkTarget(source, target, "status.open", false)) {
			return 0;
		}
		if (target.online().isEmpty()) {
			source.sendFailure(Component.literal(target.name() + " is offline; the status panel shows live players. Use /invsee for saved data."));
			return 0;
		}
		StatusMenu.openFor(viewer, target.online().get());
		return 1;
	}

	/**
	 * Switching between an offline player's saved inventory and ender chest from the offline menu: the
	 * same checks and audit as the command.
	 */
	static void openOfflineFromMenu(ServerPlayer viewer, NameAndId profile, OfflineMenu.View view, boolean edit) {
		CommandSourceStack source = viewer.createCommandSourceStack();
		Target target = new Target(profile.id(), profile.name(), Optional.empty());
		if (source.getServer().getPlayerList().getPlayer(profile.id()) != null) {
			source.sendFailure(Component.literal(profile.name() + " is online now; use /invsee or /ecsee again."));
			return;
		}
		if (checkTarget(source, target, "invsee.offline.open", edit)) {
			openOffline(source, viewer, target, view, edit);
		}
	}

	private static int openOffline(CommandSourceStack source, ServerPlayer viewer, Target target, OfflineMenu.View view, boolean edit) {
		if (!MejtaUtils.config().get(UtilsSettings.OFFLINE_VIEW)
				|| !require(source, target, view == OfflineMenu.View.INVENTORY ? UtilsPermissions.INVSEE_OFFLINE : UtilsPermissions.ECSEE_VIEW,
						"invsee.offline.open")) {
			if (!MejtaUtils.config().get(UtilsSettings.OFFLINE_VIEW)) {
				source.sendFailure(Component.literal(target.name() + " is offline, and viewing offline players is turned off (invsee.offline.enabled)."));
			}
			return 0;
		}
		if (edit && MejtaUtils.staffMode().isActive(target.id())) {
			source.sendFailure(Component.literal(target.name() + " has a staff-mode session: their survival inventory is in the "
					+ "staff-mode save, not in their player file. Opening read-only."));
			edit = false;
		}
		if (edit && !OfflineMenu.canQueue(viewer)) {
			source.sendFailure(Component.literal("Queued offline changes need " + UtilsPermissions.INVSEE_OFFLINE_EDIT.node()
					+ " and the experimental setting invsee.offline.edit; opening read-only."));
			edit = false;
		}
		Optional<OfflineSnapshot> snapshot = OfflineSnapshot.load(source.getServer(), target.profile());
		if (snapshot.isEmpty()) {
			source.sendFailure(Component.literal(target.name() + " has never joined this world."));
			return 0;
		}
		OfflineMenu.open(viewer, snapshot.get(), view, edit);
		UtilsAudit.record("invsee.offline.open", source, target, AuditOutcome.SUCCESS,
				Map.of("container", view.name(), "mode", edit ? "queue changes" : "view"));
		return 1;
	}

	/** Protected-player rule; refusals are audited. */
	private static boolean checkTarget(CommandSourceStack source, Target target, String action, boolean edit) {
		if (!Targets.mayActOn(source, target)) {
			source.sendFailure(Component.literal(target.name() + " is protected (" + UtilsPermissions.PROTECTED.node() + ")."));
			UtilsAudit.record(action, source, target, AuditOutcome.DENIED, Map.of("reason", "protected target", "mode", edit ? "edit" : "view"));
			return false;
		}
		return true;
	}

	private static boolean require(CommandSourceStack source, Target target, MejtaPermission permission, String action) {
		if (MejtaCoreAPI.hasPermission(source, permission)) {
			return true;
		}
		source.sendFailure(Component.literal("You need " + permission.node() + " for that."));
		UtilsAudit.record(action, source, target, AuditOutcome.DENIED, Map.of("missing_permission", permission.node()));
		return false;
	}

	/** From menus and staff tools: permission-checked again here. Your own inventory is always view-only. */
	public static void openInventory(ServerPlayer viewer, ServerPlayer target, boolean edit) {
		edit = edit && viewer != target && !MejtaUtils.staffMode().isActive(target.getUUID());
		if (!MejtaCoreAPI.hasPermission(viewer, edit ? UtilsPermissions.INVSEE_EDIT : UtilsPermissions.INVSEE_VIEW)
				|| !Targets.mayActOn(viewer, target)) {
			viewer.sendSystemMessage(Component.literal("You may not inspect " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.RED));
			return;
		}
		LiveInventoryMenu.open(viewer, target, edit);
		UtilsAudit.record("invsee.open", viewer, target, AuditOutcome.SUCCESS, Map.of("mode", edit ? "edit" : "view", "online", true));
	}

	public static void openEnderChest(ServerPlayer viewer, ServerPlayer target, boolean edit) {
		edit = edit && !MejtaUtils.staffMode().isActive(target.getUUID());
		if (!MejtaCoreAPI.hasPermission(viewer, edit ? UtilsPermissions.ECSEE_EDIT : UtilsPermissions.ECSEE_VIEW)
				|| !Targets.mayActOn(viewer, target)) {
			viewer.sendSystemMessage(Component.literal("You may not inspect " + target.getGameProfile().name() + ".").withStyle(ChatFormatting.RED));
			return;
		}
		LiveEnderChestMenu.open(viewer, target, edit);
		UtilsAudit.record("ecsee.open", viewer, target, AuditOutcome.SUCCESS, Map.of("mode", edit ? "edit" : "view", "online", true));
	}

	/**
	 * Closes every edit menu (live inventory or ender chest) open on {@code target}, e.g. when they enter
	 * staff mode. Each one audits its changes as it closes. View menus stay open.
	 */
	public static void closeEditMenusOn(ServerPlayer target) {
		for (ServerPlayer viewer : List.copyOf(target.level().getServer().getPlayerList().getPlayers())) {
			if (viewer.containerMenu instanceof InspectMenu menu && menu.isEditable() && menu.isOn(target)) {
				viewer.sendSystemMessage(Component.literal("The edit menu of " + target.getGameProfile().name()
						+ " was closed: they entered staff mode.").withStyle(ChatFormatting.YELLOW));
				menu.close();
			}
		}
	}
}
