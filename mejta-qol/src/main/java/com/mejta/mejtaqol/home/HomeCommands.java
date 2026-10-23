package com.mejta.mejtaqol.home;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Names;
import com.mejta.mejtaqol.util.Players;

/** {@code /sethome}, {@code /home}, {@code /delhome} and {@code /homes}. */
public final class HomeCommands {
	/** The home {@code /sethome} and {@code /home} use without a name. */
	public static final String DEFAULT_NAME = "home";

	private static final SimpleCommandExceptionType ONE_PLAYER = new SimpleCommandExceptionType(Component.literal("Name exactly one player"));

	private HomeCommands() {
	}

	public static void register() {
		String id = MejtaQoL.ID;
		MejtaCoreAPI.registerRootCommand(id, "sethome", (root, ctx) -> root
				.requires(Cmds.available(Features.HOME))
				.executes(c -> setHome(c.getSource().getPlayerOrException(), DEFAULT_NAME))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(HomeCommands::suggestOwn)
						.executes(c -> setHome(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
		MejtaCoreAPI.registerRootCommand(id, "home", (root, ctx) -> root
				.requires(Cmds.available(Features.HOME))
				.executes(c -> home(c.getSource().getPlayerOrException(), Optional.empty()))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(HomeCommands::suggestOwn)
						.executes(c -> home(c.getSource().getPlayerOrException(), Optional.of(StringArgumentType.getString(c, "name"))))));
		MejtaCoreAPI.registerRootCommand(id, "delhome", (root, ctx) -> root
				.requires(Cmds.available(Features.HOME))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(HomeCommands::suggestOwn)
						.executes(c -> deleteOwn(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
		MejtaCoreAPI.registerRootCommand(id, "homes", (root, ctx) -> root
				.requires(Cmds.available(Features.HOME))
				.executes(c -> list(c.getSource().getPlayerOrException()))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.requires(MejtaCoreAPI.requires(QolPermissions.HOMES_OTHERS))
						.executes(c -> listOther(c))
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(c -> visitOther(c))
								.then(Commands.literal("delete").executes(c -> deleteOther(c))))));
	}

	public static int maxHomes(ServerPlayer player) {
		int byRank = QolSettings.forRank(MejtaQoL.config(), QolSettings.HOMES_MAX, player.getUUID());
		return MejtaCoreAPI.hasPermission(player, QolPermissions.HOMES_EXTRA)
				? Math.max(byRank, MejtaQoL.config().get(QolSettings.HOMES_MAX_EXTRA)) : byRank;
	}

	public static int setHome(ServerPlayer player, String rawName) {
		Optional<String> name = Names.normalize(rawName);
		if (name.isEmpty()) {
			player.sendSystemMessage(Msg.error("Home names use letters, digits, _ and - (at most 16)."));
			return 0;
		}
		String dimension = Locations.dimension(player.level());
		if (Names.list(MejtaQoL.config().get(QolSettings.HOMES_BLOCKED_DIMENSIONS), true).contains(dimension)) {
			player.sendSystemMessage(Msg.error("You can't set a home in this dimension."));
			return 0;
		}
		HomeBook book = MejtaQoL.homes().book(player.getUUID());
		int max = maxHomes(player);
		Location here = Locations.of(player);
		HomeBook.SetResult result = book.set(name.get(), here, max);
		switch (result) {
			case LIMIT_REACHED -> {
				player.sendSystemMessage(Msg.error("You already have " + book.size() + " of " + max
						+ " homes. Delete one with /delhome or move one by setting it again."));
				return 0;
			}
			case INVALID_NAME -> {
				player.sendSystemMessage(Msg.error("Invalid home name."));
				return 0;
			}
			default -> {
			}
		}
		MejtaQoL.homes().save(player.getUUID());
		player.sendSystemMessage(Msg.success((result == HomeBook.SetResult.MOVED ? "Moved home " : "Set home ")).append(Msg.highlight(name.get()))
				.append(Msg.success(" at " + here.describe() + ". (" + book.size() + "/" + max + ")")));
		return 1;
	}

	public static int home(ServerPlayer player, Optional<String> rawName) {
		HomeBook book = MejtaQoL.homes().book(player.getUUID());
		String name;
		if (rawName.isPresent()) {
			Optional<String> n = Names.normalize(rawName.get());
			if (n.isEmpty() || book.get(n.get()).isEmpty()) {
				player.sendSystemMessage(Msg.error("You have no home called " + rawName.get() + ".").append(homeList(book)));
				return 0;
			}
			name = n.get();
		} else {
			Optional<Map.Entry<String, Location>> def = book.defaultHome(DEFAULT_NAME);
			if (def.isEmpty()) {
				if (book.size() == 0) {
					player.sendSystemMessage(Msg.error("You have no homes yet. Set one with /sethome [name]."));
				} else {
					player.sendSystemMessage(Msg.info("Which home?").append(homeList(book)));
				}
				return 0;
			}
			name = def.get().getKey();
		}
		UUID owner = player.getUUID();
		return MejtaQoL.teleports().request(player, Features.HOME, Component.literal("home " + name), t ->
				MejtaQoL.homes().book(owner).get(name).map(Teleports.Result::to)
						.orElseGet(() -> Teleports.Result.fail("Your home " + name + " was deleted."))) ? 1 : 0;
	}

	public static int deleteOwn(ServerPlayer player, String rawName) {
		HomeBook book = MejtaQoL.homes().book(player.getUUID());
		if (!book.delete(rawName)) {
			player.sendSystemMessage(Msg.error("You have no home called " + rawName + "."));
			return 0;
		}
		MejtaQoL.homes().save(player.getUUID());
		player.sendSystemMessage(Msg.success("Deleted home " + rawName.toLowerCase(Locale.ROOT) + "."));
		return 1;
	}

	private static int list(ServerPlayer player) {
		HomeBook book = MejtaQoL.homes().book(player.getUUID());
		if (MejtaQoL.config().get(QolSettings.HOMES_MENU)) {
			HomesMenu.open(player, player.getUUID(), Players.name(player), false);
			return book.size();
		}
		if (book.size() == 0) {
			player.sendSystemMessage(Msg.info("You have no homes yet. Set one with /sethome [name]."));
			return 0;
		}
		player.sendSystemMessage(Msg.highlight("Your homes (" + book.size() + "/" + maxHomes(player) + "):").append(homeList(book)));
		return book.size();
	}

	/** {@code " [a] [b]"}: each runs {@code /home <name>}. */
	static MutableComponent homeList(HomeBook book) {
		MutableComponent out = Component.empty();
		for (String name : book.all().keySet()) {
			out.append(Component.literal(" ")).append(Msg.button(name, "/home " + name, ChatFormatting.AQUA));
		}
		return out;
	}

	// ---------------------------------------------------------------- other players' homes (staff)

	private static NameAndId profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, "player");
		if (profiles.size() != 1) {
			throw ONE_PLAYER.create();
		}
		return profiles.iterator().next();
	}

	private static int listOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer staff = c.getSource().getPlayerOrException();
		NameAndId owner = profile(c);
		HomeBook book = MejtaQoL.homes().book(owner.id());
		if (MejtaQoL.config().get(QolSettings.HOMES_MENU)) {
			HomesMenu.open(staff, owner.id(), owner.name(), true);
		} else {
			MutableComponent line = Msg.highlight(owner.name() + "'s homes (" + book.size() + "):");
			for (Map.Entry<String, Location> e : book.all().entrySet()) {
				line.append(Component.literal(" ")).append(Msg.button(e.getKey(), "/homes " + owner.name() + " " + e.getKey(), ChatFormatting.AQUA));
			}
			staff.sendSystemMessage(line);
		}
		forgetIfOffline(c, owner.id());
		return book.size();
	}

	private static int visitOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer staff = c.getSource().getPlayerOrException();
		NameAndId owner = profile(c);
		boolean started = visitOther(staff, owner, StringArgumentType.getString(c, "name"));
		forgetIfOffline(c, owner.id());
		return started ? 1 : 0;
	}

	static boolean visitOther(ServerPlayer staff, NameAndId owner, String name) {
		Optional<Location> home = MejtaQoL.homes().book(owner.id()).get(name);
		if (home.isEmpty()) {
			staff.sendSystemMessage(Msg.error(owner.name() + " has no home called " + name + "."));
			return false;
		}
		Location to = home.get();
		audit(staff, "homes.visit", owner, name, to);
		return MejtaQoL.teleports().request(staff, Features.HOME_OTHERS, Component.literal(owner.name() + "'s home " + name.toLowerCase(Locale.ROOT)),
				t -> Teleports.Result.to(to));
	}

	private static int deleteOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer staff = c.getSource().getPlayerOrException();
		NameAndId owner = profile(c);
		String name = StringArgumentType.getString(c, "name");
		return deleteOther(staff, owner, name) ? 1 : 0;
	}

	static boolean deleteOther(ServerPlayer staff, NameAndId owner, String name) {
		HomeBook book = MejtaQoL.homes().book(owner.id());
		Optional<Location> home = book.get(name);
		if (home.isEmpty() || !book.delete(name)) {
			staff.sendSystemMessage(Msg.error(owner.name() + " has no home called " + name + "."));
			return false;
		}
		MejtaQoL.homes().save(owner.id());
		if (staff.level().getServer().getPlayerList().getPlayer(owner.id()) == null) {
			MejtaQoL.homes().forget(owner.id());
		}
		audit(staff, "homes.delete", owner, name, home.get());
		staff.sendSystemMessage(Msg.success("Deleted " + owner.name() + "'s home " + name.toLowerCase(Locale.ROOT) + "."));
		return true;
	}

	private static void forgetIfOffline(CommandContext<CommandSourceStack> c, UUID owner) {
		if (c.getSource().getServer().getPlayerList().getPlayer(owner) == null) {
			MejtaQoL.homes().forget(owner);
		}
	}

	private static void audit(ServerPlayer staff, String action, NameAndId owner, String home, Location where) {
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaQoL.ID, action)
				.actor(MejtaCoreAPI.actor(staff))
				.target(owner.id(), owner.name())
				.outcome(AuditOutcome.SUCCESS)
				.detail("home", home.toLowerCase(Locale.ROOT))
				.detail("location", where.describe())
				.build());
	}

	private static CompletableFuture<Suggestions> suggestOwn(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		ServerPlayer self = c.getSource().getPlayer();
		if (self != null && MejtaQoL.homes() != null) {
			String typed = b.getRemaining().toLowerCase(Locale.ROOT);
			List<String> names = List.copyOf(MejtaQoL.homes().book(self.getUUID()).all().keySet());
			for (String n : names) {
				if (n.startsWith(typed)) {
					b.suggest(n);
				}
			}
		}
		return b.buildFuture();
	}
}
