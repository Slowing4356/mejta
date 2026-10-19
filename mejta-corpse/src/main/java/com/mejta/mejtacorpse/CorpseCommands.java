package com.mejta.mejtacorpse;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.world.CorpseEntities;

/**
 * {@code /corpse}: your corpses; {@code /corpse open <id>} from afar (staff, or the owner where allowed);
 * staff: {@code /corpse list <player>} and {@code /corpse tp <id>}.
 */
public final class CorpseCommands {
	private static final SimpleCommandExceptionType ONE_PLAYER = new SimpleCommandExceptionType(Component.literal("Name exactly one player"));

	private CorpseCommands() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaCorpse.ID, "corpse", (root, ctx) -> root
				.requires(MejtaCoreAPI.requires(CorpsePermissions.USE))
				.executes(c -> listOwn(c.getSource().getPlayerOrException()))
				.then(Commands.literal("open")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(CorpseCommands::suggestIds)
								.executes(CorpseCommands::open)))
				.then(Commands.literal("list")
						.requires(MejtaCoreAPI.requires(CorpsePermissions.ADMIN))
						.then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(CorpseCommands::listOther)))
				.then(Commands.literal("tp")
						.requires(MejtaCoreAPI.requires(CorpsePermissions.ADMIN))
						.then(Commands.argument("id", StringArgumentType.word()).suggests(CorpseCommands::suggestIds)
								.executes(CorpseCommands::teleport))));
	}

	private static int listOwn(ServerPlayer player) {
		List<CorpseRecord> corpses = MejtaCorpse.manager().store().ofOwner(player.getUUID());
		if (corpses.isEmpty()) {
			player.sendSystemMessage(Component.literal("You have no corpses.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		player.sendSystemMessage(Component.literal("Your corpses (" + corpses.size() + "):").withStyle(ChatFormatting.GOLD));
		for (CorpseRecord r : corpses) {
			player.sendSystemMessage(MejtaCorpse.manager().describe(r, false, MejtaCorpse.manager().mayOpenFromAfar(player, r)));
		}
		return corpses.size();
	}

	private static int listOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, "player");
		if (profiles.size() != 1) {
			throw ONE_PLAYER.create();
		}
		NameAndId owner = profiles.iterator().next();
		List<CorpseRecord> corpses = MejtaCorpse.manager().store().ofOwner(owner.id());
		CommandSourceStack source = c.getSource();
		source.sendSystemMessage(Component.literal(owner.name() + "'s corpses (" + corpses.size() + "):").withStyle(ChatFormatting.GOLD));
		ServerPlayer self = source.getPlayer();
		for (CorpseRecord r : corpses) {
			source.sendSystemMessage(MejtaCorpse.manager().describe(r, false, self != null));
		}
		return corpses.size();
	}

	private static Optional<CorpseRecord> find(CommandSourceStack source, String id) {
		Optional<CorpseRecord> r = MejtaCorpse.manager().store().get(id.toLowerCase(Locale.ROOT));
		if (r.isEmpty()) {
			source.sendFailure(Component.literal("No corpse with ID " + id + "."));
		}
		return r;
	}

	private static int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Optional<CorpseRecord> r = find(c.getSource(), StringArgumentType.getString(c, "id"));
		if (r.isEmpty()) {
			return 0;
		}
		boolean ownerOrAdmin = r.get().owner().equals(player.getUUID()) || MejtaCoreAPI.hasPermission(player, CorpsePermissions.ADMIN);
		if (!ownerOrAdmin) {
			c.getSource().sendFailure(Component.literal("That is not your corpse."));
			return 0;
		}
		return MejtaCorpse.manager().open(player, r.get(), true) ? 1 : 0;
	}

	private static int teleport(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Optional<CorpseRecord> r = find(c.getSource(), StringArgumentType.getString(c, "id"));
		if (r.isEmpty()) {
			return 0;
		}
		Optional<ServerLevel> level = CorpseEntities.level(c.getSource().getServer(), r.get());
		if (level.isEmpty()) {
			c.getSource().sendFailure(Component.literal("That corpse's dimension no longer exists."));
			return 0;
		}
		double y = Math.max(r.get().y(), level.get().getMinY() + 1);
		player.teleport(new TeleportTransition(level.get(), new Vec3(r.get().x(), y, r.get().z()), Vec3.ZERO, player.getYRot(),
				player.getXRot(), TeleportTransition.DO_NOTHING));
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCorpse.ID, "corpse.teleport")
				.actor(MejtaCoreAPI.actor(player))
				.target(r.get().owner(), r.get().ownerName())
				.outcome(AuditOutcome.SUCCESS)
				.detail("corpse", r.get().id())
				.build());
		c.getSource().sendSuccess(() -> Component.literal("Teleported to " + r.get().ownerName() + "'s corpse."), false);
		return 1;
	}

	/** IDs of the source's own corpses, or every corpse for staff. */
	private static CompletableFuture<Suggestions> suggestIds(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		CorpseManager manager = MejtaCorpse.managerOrNull();
		ServerPlayer self = c.getSource().getPlayer();
		if (manager != null) {
			boolean admin = MejtaCoreAPI.hasPermission(c.getSource(), CorpsePermissions.ADMIN);
			UUID me = self == null ? null : self.getUUID();
			for (CorpseRecord r : manager.store().all()) {
				if ((admin || r.owner().equals(me)) && r.id().startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
					b.suggest(r.id());
				}
			}
		}
		return b.buildFuture();
	}
}
