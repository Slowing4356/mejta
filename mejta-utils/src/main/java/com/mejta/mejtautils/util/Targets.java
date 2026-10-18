package com.mejta.mejtautils.util;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtautils.UtilsPermissions;

/**
 * Resolves the {@code <player>} argument of MejtaUtils' commands.
 *
 * <p>Players are resolved by the game itself ({@link GameProfileArgument}: online players, the
 * server's profile cache, selectors where the source may use them). MejtaUtils never turns player
 * input into a file path: offline data is read only through the game's own player-data storage for
 * the resolved UUID, inside the current world's folder.
 */
public final class Targets {
	private static final SimpleCommandExceptionType ONE_PLAYER =
			new SimpleCommandExceptionType(Component.literal("Name exactly one player"));

	/** @param online the player if they are online now */
	public record Target(UUID id, String name, Optional<ServerPlayer> online) {
		public NameAndId profile() {
			return new NameAndId(id, name);
		}
	}

	private Targets() {
	}

	public static Target single(CommandContext<CommandSourceStack> ctx, String argument) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, argument);
		if (profiles.size() != 1) {
			throw ONE_PLAYER.create();
		}
		NameAndId profile = profiles.iterator().next();
		MinecraftServer server = ctx.getSource().getServer();
		ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
		return new Target(profile.id(), online != null ? online.getGameProfile().name() : profile.name(), Optional.ofNullable(online));
	}

	/**
	 * Whether {@code source} may act on {@code target}: protected players (mejtautils.protected) can only
	 * be acted on by sources that are protected too. Nobody is protected from themselves.
	 */
	public static boolean mayActOn(CommandSourceStack source, Target target) {
		ServerPlayer self = source.getPlayer();
		if (self != null && self.getUUID().equals(target.id())) {
			return true;
		}
		boolean targetProtected = target.online()
				.map(p -> MejtaCoreAPI.hasPermission(p, UtilsPermissions.PROTECTED))
				.orElseGet(() -> MejtaCoreAPI.hasPermission(source.getServer(), target.profile(), UtilsPermissions.PROTECTED));
		return !targetProtected || MejtaCoreAPI.hasPermission(source, UtilsPermissions.PROTECTED);
	}

	public static boolean mayActOn(ServerPlayer actor, ServerPlayer target) {
		return actor == target || !MejtaCoreAPI.hasPermission(target, UtilsPermissions.PROTECTED)
				|| MejtaCoreAPI.hasPermission(actor, UtilsPermissions.PROTECTED);
	}
}
