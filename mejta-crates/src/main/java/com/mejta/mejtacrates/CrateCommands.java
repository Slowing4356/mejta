package com.mejta.mejtacrates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.CrateStore;
import com.mejta.mejtacrates.data.PlayerKeys;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.data.Roll;
import com.mejta.mejtacrates.menu.CrateMenus;
import com.mejta.mejtacrates.util.ItemJson;

/**
 * {@code /crates}: the crate menu, opening, previews and keys for players; {@code give}, {@code giveall} and
 * {@code take} for staff and the console (donation stores); {@code /crates admin} to edit crates and place crate
 * blocks. Also under {@code /mejtaadmin mejtacrates crates}.
 */
public final class CrateCommands {
	private static final int MAX_AMOUNT = 10_000;
	private static final List<String> COLORS = List.of("BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY",
			"DARK_GRAY", "BLUE", "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE");

	/** Who gets keys: a known player, or a name that has never joined. */
	private record Recipient(Optional<UUID> id, String name) {
	}

	private CrateCommands() {
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaCrates.ID, "crates", (root, ctx) -> root
				.requires(MejtaCoreAPI.requires(CratePermissions.USE))
				.executes(c -> {
					CrateMenus.list(c.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("open")
						.then(crateArgument().executes(CrateCommands::open)))
				.then(Commands.literal("preview")
						.then(crateArgument().executes(c -> {
							CrateMenus.preview(c.getSource().getPlayerOrException(), crate(c), 0, false);
							return 1;
						})))
				.then(Commands.literal("keys")
						.executes(c -> showKeys(c.getSource(), c.getSource().getPlayerOrException().getUUID(),
								c.getSource().getPlayerOrException().getGameProfile().name()))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.requires(MejtaCoreAPI.requires(CratePermissions.ADMIN))
								.executes(CrateCommands::showOthersKeys)))
				.then(Commands.literal("give")
						.requires(MejtaCoreAPI.requires(CratePermissions.GIVE))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.then(withAmountAndKind(crateArgument(), (c, amount, physical) -> give(c, amount, physical)))))
				.then(Commands.literal("giveall")
						.requires(MejtaCoreAPI.requires(CratePermissions.GIVE))
						.then(withAmountAndKind(crateArgument(), CrateCommands::giveAll)))
				.then(Commands.literal("take")
						.requires(MejtaCoreAPI.requires(CratePermissions.GIVE))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.then(crateArgument()
										.then(Commands.argument("amount", IntegerArgumentType.integer(1, PlayerKeys.MAX_KEYS))
												.executes(CrateCommands::take)))))
				.then(admin()));
	}

	// ---------------------------------------------------------------- players

	private static int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		Crate crate = crate(c);
		if (!MejtaCrates.config().get(CrateSettings.OPEN_FROM_MENU)) {
			c.getSource().sendFailure(Component.literal("Open crates at their crate block."));
			return 0;
		}
		CrateService.OpenOutcome outcome = MejtaCrates.service().openVirtual(player, crate, true);
		CrateMenus.tell(player, crate, outcome);
		return outcome == CrateService.OpenOutcome.STARTED ? 1 : 0;
	}

	private static int showOthersKeys(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		int shown = 0;
		for (NameAndId profile : GameProfileArgument.getGameProfiles(c, "player")) {
			shown += showKeys(c.getSource(), profile.id(), profile.name());
		}
		return shown;
	}

	private static int showKeys(CommandSourceStack source, UUID player, String name) {
		PlayerKeys keys = MejtaCrates.service().store().player(player, name);
		ServerPlayer self = source.getPlayer();
		boolean own = self != null && self.getUUID().equals(player);
		if (keys.keys().isEmpty() && keys.physical().isEmpty() && keys.pending().isEmpty()) {
			source.sendSystemMessage(Component.literal(own ? "You have no crate keys." : name + " has no crate keys.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		source.sendSystemMessage(Component.literal(own ? "Your crate keys:" : name + "'s crate keys:").withStyle(ChatFormatting.GOLD));
		for (var e : keys.keys().entrySet()) {
			MutableComponent line = Component.literal("  " + e.getValue() + "x ").withStyle(ChatFormatting.WHITE);
			Optional<Crate> crate = MejtaCrates.service().store().crate(e.getKey());
			line.append(crate.<Component>map(CrateTexts::name).orElseGet(() -> Component.literal(e.getKey() + " (deleted crate)").withStyle(ChatFormatting.DARK_GRAY)));
			source.sendSystemMessage(line);
		}
		if (!keys.physical().isEmpty()) {
			source.sendSystemMessage(Component.literal("  Physical keys waiting for the next join: " + keys.physical()).withStyle(ChatFormatting.GRAY));
		}
		if (!keys.pending().isEmpty()) {
			source.sendSystemMessage(Component.literal("  Unfinished openings: " + keys.pending().size()).withStyle(ChatFormatting.GRAY));
		}
		return keys.totalKeys();
	}

	// ---------------------------------------------------------------- give, giveall, take

	@FunctionalInterface
	private interface GiveAction {
		int run(CommandContext<CommandSourceStack> c, int amount, boolean physical) throws CommandSyntaxException;
	}

	/** {@code <crate> [amount] [virtual|physical]}. */
	private static ArgumentBuilder<CommandSourceStack, ?> withAmountAndKind(ArgumentBuilder<CommandSourceStack, ?> crate, GiveAction action) {
		return crate
				.executes(c -> action.run(c, 1, false))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
						.executes(c -> action.run(c, IntegerArgumentType.getInteger(c, "amount"), false))
						.then(Commands.literal("virtual").executes(c -> action.run(c, IntegerArgumentType.getInteger(c, "amount"), false)))
						.then(Commands.literal("physical").executes(c -> action.run(c, IntegerArgumentType.getInteger(c, "amount"), true))));
	}

	private static int give(CommandContext<CommandSourceStack> c, int amount, boolean physical) throws CommandSyntaxException {
		Crate crate = crate(c);
		CommandSourceStack source = c.getSource();
		AuditActor actor = MejtaCoreAPI.actor(source);
		CrateService service = MejtaCrates.service();
		int given = 0;
		for (Recipient r : recipients(c)) {
			boolean ok;
			String note;
			if (r.id().isEmpty()) {
				ok = service.giveByName(actor, r.name(), crate, amount, physical);
				note = " (has never joined: given at their first join)";
			} else if (physical) {
				ok = service.givePhysical(source.getServer(), actor, r.id().get(), r.name(), crate, amount);
				note = source.getServer().getPlayerList().getPlayer(r.id().get()) == null ? " (offline: given at their next join)" : "";
			} else {
				ok = service.giveVirtual(source.getServer(), actor, r.id().get(), r.name(), crate, amount);
				note = "";
			}
			if (ok) {
				given++;
				String text = "Gave " + amount + " " + (physical ? "physical " : "") + crate.name() + " key(s) to " + r.name() + note + ".";
				source.sendSuccess(() -> Component.literal(text), false);
			} else {
				source.sendFailure(Component.literal("Could not give keys to " + r.name() + ": the data could not be saved. See the server log."));
			}
		}
		return given;
	}

	private static int giveAll(CommandContext<CommandSourceStack> c, int amount, boolean physical) throws CommandSyntaxException {
		Crate crate = crate(c);
		CommandSourceStack source = c.getSource();
		AuditActor actor = MejtaCoreAPI.actor(source);
		int given = 0;
		for (ServerPlayer p : List.copyOf(source.getServer().getPlayerList().getPlayers())) {
			boolean ok = physical
					? MejtaCrates.service().givePhysical(source.getServer(), actor, p.getUUID(), p.getGameProfile().name(), crate, amount)
					: MejtaCrates.service().giveVirtual(source.getServer(), actor, p.getUUID(), p.getGameProfile().name(), crate, amount);
			if (ok) {
				given++;
			}
		}
		int count = given;
		source.sendSuccess(() -> Component.literal("Gave " + amount + " " + crate.name() + " key(s) to " + count + " online player(s)."), true);
		return given;
	}

	private static int take(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Crate crate = crate(c);
		int amount = IntegerArgumentType.getInteger(c, "amount");
		CommandSourceStack source = c.getSource();
		int total = 0;
		for (NameAndId profile : GameProfileArgument.getGameProfiles(c, "player")) {
			int taken = MejtaCrates.service().take(source.getServer(), MejtaCoreAPI.actor(source), profile.id(), profile.name(), crate, amount);
			if (taken < 0) {
				source.sendFailure(Component.literal("Could not take keys from " + profile.name() + ": the data could not be saved."));
			} else {
				total += taken;
				source.sendSuccess(() -> Component.literal("Took " + taken + " " + crate.name() + " key(s) from " + profile.name() + "."), false);
			}
		}
		return total;
	}

	/**
	 * The players named by the {@code player} argument. A plain name the server does not know (someone who has
	 * never joined, e.g. a buyer in a web store) is accepted as a name: the keys wait for their first join.
	 */
	private static List<Recipient> recipients(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		try {
			List<Recipient> out = new ArrayList<>();
			for (NameAndId profile : GameProfileArgument.getGameProfiles(c, "player")) {
				out.add(new Recipient(Optional.of(profile.id()), profile.name()));
			}
			return out;
		} catch (CommandSyntaxException e) {
			String raw = rawArgument(c, "player");
			if (raw != null && CrateStore.PLAYER_NAME.matcher(raw).matches()) {
				return List.of(new Recipient(Optional.empty(), raw));
			}
			throw e;
		}
	}

	private static String rawArgument(CommandContext<CommandSourceStack> c, String name) {
		for (ParsedCommandNode<CommandSourceStack> node : c.getNodes()) {
			if (node.getNode() instanceof ArgumentCommandNode<?, ?> argument && argument.getName().equals(name)) {
				return node.getRange().get(c.getInput());
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- admin

	private static ArgumentBuilder<CommandSourceStack, ?> admin() {
		return Commands.literal("admin")
				.requires(MejtaCoreAPI.requires(CratePermissions.ADMIN))
				.then(Commands.literal("list").executes(c -> list(c.getSource())))
				.then(Commands.literal("info").then(crateArgument().executes(c -> info(c.getSource(), crate(c)))))
				.then(Commands.literal("reload").executes(c -> {
					MejtaCrates.service().store().load();
					int n = MejtaCrates.service().store().crates().size();
					c.getSource().sendSuccess(() -> Component.literal("Reloaded " + n + " crate(s) and the crate blocks from disk."), true);
					return n;
				}))
				.then(Commands.literal("create")
						.then(Commands.argument("id", StringArgumentType.word())
								.executes(c -> create(c, ""))
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(c -> create(c, StringArgumentType.getString(c, "name"))))))
				.then(Commands.literal("delete")
						.then(crateArgument()
								.executes(c -> {
									Crate crate = crate(c);
									c.getSource().sendSystemMessage(Component.literal("This deletes " + crate.name() + " with its " + crate.rewards().size()
											+ " reward(s) and crate blocks. Run /crates admin delete " + crate.id() + " confirm to do it.").withStyle(ChatFormatting.YELLOW));
									return 0;
								})
								.then(Commands.literal("confirm").executes(c -> {
									CrateEditor.delete(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c));
									return 1;
								}))))
				.then(Commands.literal("rename")
						.then(crateArgument().then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
							String name = StringArgumentType.getString(c, "name");
							CrateEditor.save(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c).withName(name), "renamed to " + name);
							return 1;
						}))))
				.then(Commands.literal("color")
						.then(crateArgument().then(Commands.argument("color", StringArgumentType.word())
								.suggests((c, b) -> suggest(b, COLORS))
								.executes(c -> {
									String color = StringArgumentType.getString(c, "color").toUpperCase(Locale.ROOT);
									if (!COLORS.contains(color)) {
										c.getSource().sendFailure(Component.literal("Unknown colour " + color + "."));
										return 0;
									}
									CrateEditor.save(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c).withColor(color), "colour " + color);
									return 1;
								}))))
				.then(Commands.literal("icon")
						.then(crateArgument().executes(c -> {
							ItemStack hand = heldItem(c);
							CrateEditor.save(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c).withIcon(Optional.of(encode(c, hand.copyWithCount(1)))),
									"icon " + ItemJson.describe(hand.copyWithCount(1)));
							return 1;
						})))
				.then(Commands.literal("keyitem")
						.then(crateArgument().executes(c -> {
							ItemStack hand = heldItem(c);
							if (KeyItems.isKey(hand)) {
								c.getSource().sendFailure(Component.literal("Hold a plain item, not a key."));
								return 0;
							}
							ItemStack base = new ItemStack(hand.getItem());
							CrateEditor.save(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c).withKeyItem(Optional.of(encode(c, base))),
									"new physical keys are " + ItemJson.describe(base) + " (existing keys still work)");
							return 1;
						})))
				.then(Commands.literal("add")
						.then(Commands.literal("item").then(crateArgument().then(weightArgument().executes(c -> {
							ItemStack hand = heldItem(c);
							if (KeyItems.isKey(hand)) {
								c.getSource().sendFailure(Component.literal("Keys cannot be rewards: they would carry the serial of your batch. Give keys with a command reward instead."));
								return 0;
							}
							CrateEditor.addReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), Reward.ofItem(weight(c), encode(c, hand.copy())),
									ItemJson.describe(hand));
							return 1;
						}))))
						.then(Commands.literal("command").then(crateArgument().then(weightArgument()
								.then(Commands.argument("command", StringArgumentType.greedyString()).executes(c -> {
									String command = stripSlash(StringArgumentType.getString(c, "command"));
									ServerPlayer player = c.getSource().getPlayer();
									Optional<com.google.gson.JsonElement> display = player != null && !player.getMainHandItem().isEmpty()
											? Optional.of(encode(c, player.getMainHandItem().copyWithCount(1))) : Optional.empty();
									CrateEditor.addReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), Reward.ofCommand(weight(c), command, display),
											"command /" + command);
									c.getSource().sendSystemMessage(Component.literal("Name it with /crates admin reward " + crate(c).id() + " "
											+ crate(c).rewards().size() + " name <name>.").withStyle(ChatFormatting.GRAY));
									return 1;
								})))))
						.then(Commands.literal("money").then(crateArgument().then(weightArgument()
								.then(Commands.argument("amount", StringArgumentType.word()).executes(c -> {
									OptionalLong money = MejtaCoreAPI.economy().parse(StringArgumentType.getString(c, "amount"));
									if (money.isEmpty()) {
										c.getSource().sendFailure(Component.literal("Not a valid amount of money."));
										return 0;
									}
									CrateEditor.addReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), Reward.ofMoney(weight(c), money.getAsLong()),
											"money " + MejtaCoreAPI.economy().format(money.getAsLong()));
									if (!MejtaCoreAPI.economy().isAvailable()) {
										c.getSource().sendSystemMessage(Component.literal("No economy mod is installed, so this reward is not drawn until one is.")
												.withStyle(ChatFormatting.YELLOW));
									}
									return 1;
								}))))))
				.then(Commands.literal("reward")
						.then(crateArgument().then(Commands.argument("number", IntegerArgumentType.integer(1, Crate.MAX_REWARDS))
								.then(Commands.literal("remove").executes(c -> {
									CrateEditor.removeReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c));
									return 1;
								}))
								.then(Commands.literal("weight").then(weightArgument().executes(c -> {
									double w = weight(c);
									CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withWeight(w), "weight " + w);
									return 1;
								})))
								.then(Commands.literal("name")
										.then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
											String name = StringArgumentType.getString(c, "name").strip();
											String shown = name.equals("-") ? "" : name.length() > 64 ? name.substring(0, 64) : name;
											CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withName(shown),
													shown.isEmpty() ? "name removed" : "name " + shown);
											return 1;
										})))
								.then(Commands.literal("broadcast")
										.then(Commands.literal("on").executes(c -> broadcast(c, Optional.of(true))))
										.then(Commands.literal("off").executes(c -> broadcast(c, Optional.of(false))))
										.then(Commands.literal("auto").executes(c -> broadcast(c, Optional.empty()))))
								.then(Commands.literal("display").executes(c -> {
									ItemStack hand = heldItem(c);
									var json = encode(c, hand.copyWithCount(1));
									CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withDisplay(Optional.of(json)),
											"shown as " + ItemJson.describe(hand.copyWithCount(1)));
									return 1;
								}))
								.then(Commands.literal("additem").executes(c -> {
									ItemStack hand = heldItem(c);
									if (KeyItems.isKey(hand)) {
										c.getSource().sendFailure(Component.literal("Keys cannot be rewards."));
										return 0;
									}
									var json = encode(c, hand.copy());
									CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withItem(json),
											"added " + ItemJson.describe(hand));
									return 1;
								}))
								.then(Commands.literal("addcommand")
										.then(Commands.argument("command", StringArgumentType.greedyString()).executes(c -> {
											String command = stripSlash(StringArgumentType.getString(c, "command"));
											CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withCommand(command),
													"added command /" + command);
											return 1;
										}))))))
				.then(Commands.literal("block")
						.then(Commands.literal("set").then(crateArgument().then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
							Crate crate = crate(c);
							BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
							ServerLevel level = c.getSource().getLevel();
							MejtaCrates.service().store().putBlock(new CrateStore.CrateBlock(dimension(level), pos.getX(), pos.getY(), pos.getZ(), crate.id()));
							CrateEditor.save(MejtaCoreAPI.actor(c.getSource()), reply(c), crate,
									"the block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + dimension(level) + " is now this crate");
							return 1;
						}))))
						.then(Commands.literal("remove").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
							BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
							boolean removed = MejtaCrates.service().store().removeBlock(dimension(c.getSource().getLevel()), pos.getX(), pos.getY(), pos.getZ());
							if (!removed) {
								c.getSource().sendFailure(Component.literal("That block is not a crate."));
								return 0;
							}
							c.getSource().sendSuccess(() -> Component.literal("That block is no longer a crate."), true);
							return 1;
						})))
						.then(Commands.literal("list").executes(c -> {
							Collection<CrateStore.CrateBlock> blocks = MejtaCrates.service().store().blocks();
							c.getSource().sendSystemMessage(Component.literal("Crate blocks (" + blocks.size() + "):").withStyle(ChatFormatting.GOLD));
							for (CrateStore.CrateBlock b : blocks) {
								c.getSource().sendSystemMessage(Component.literal("  " + b.crate() + " at " + b.position()).withStyle(ChatFormatting.GRAY));
							}
							return blocks.size();
						})));
	}

	private static int create(CommandContext<CommandSourceStack> c, String name) {
		String id = StringArgumentType.getString(c, "id").toLowerCase(Locale.ROOT);
		boolean created = CrateEditor.create(MejtaCoreAPI.actor(c.getSource()), reply(c), id, name);
		if (created) {
			c.getSource().sendSystemMessage(Component.literal("Add rewards with /crates admin add item|command|money " + id
					+ " <weight> ..., and give keys with /crates give <player> " + id + ".").withStyle(ChatFormatting.GRAY));
		}
		return created ? 1 : 0;
	}

	private static int broadcast(CommandContext<CommandSourceStack> c, Optional<Boolean> value) throws CommandSyntaxException {
		CrateEditor.updateReward(MejtaCoreAPI.actor(c.getSource()), reply(c), crate(c), index(c), r -> r.withBroadcast(value),
				"broadcast " + value.map(v -> v ? "on" : "off").orElse("auto"));
		return 1;
	}

	private static int list(CommandSourceStack source) {
		List<Crate> crates = MejtaCrates.service().store().crates();
		source.sendSystemMessage(Component.literal("Crates (" + crates.size() + "):").withStyle(ChatFormatting.GOLD));
		for (Crate crate : crates) {
			source.sendSystemMessage(Component.literal("  " + crate.id() + " ").withStyle(ChatFormatting.WHITE).append(CrateTexts.name(crate))
					.append(Component.literal("  " + crate.rewards().size() + " reward(s)").withStyle(ChatFormatting.GRAY)));
		}
		return crates.size();
	}

	private static int info(CommandSourceStack source, Crate crate) {
		HolderLookup.Provider registries = source.getServer().registryAccess();
		List<Reward> pool = CrateService.pool(crate);
		source.sendSystemMessage(Component.literal(crate.id() + ": ").withStyle(ChatFormatting.GOLD).append(CrateTexts.name(crate))
				.append(Component.literal("  " + crate.rewards().size() + " reward(s), total weight " + Roll.totalWeight(pool)).withStyle(ChatFormatting.GRAY)));
		for (int i = 0; i < crate.rewards().size(); i++) {
			Reward r = crate.rewards().get(i);
			StringBuilder what = new StringBuilder();
			if (!r.items().isEmpty()) {
				what.append(r.items().size()).append(" item stack(s) ");
			}
			if (!r.commands().isEmpty()) {
				what.append(r.commands().size()).append(" command(s) ");
			}
			if (r.paysMoney()) {
				what.append(MejtaCoreAPI.economy().format(r.money())).append(' ');
			}
			String chance = pool.contains(r) ? Roll.percent(Roll.chance(r, pool)) : "not drawn (no economy)";
			source.sendSystemMessage(Component.literal("  #" + (i + 1) + " " + CrateTexts.rewardName(registries, r)).withStyle(ChatFormatting.AQUA)
					.append(Component.literal("  weight " + r.weight() + ", " + chance + "  " + what.toString().strip()
							+ r.broadcast().map(b -> b ? "  broadcast" : "  no broadcast").orElse("")).withStyle(ChatFormatting.GRAY)));
		}
		return crate.rewards().size();
	}

	// ---------------------------------------------------------------- arguments

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> crateArgument() {
		return Commands.argument("crate", StringArgumentType.word()).suggests(CrateCommands::suggestCrates);
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> weightArgument() {
		return Commands.argument("weight", DoubleArgumentType.doubleArg(0.000001, Reward.MAX_WEIGHT));
	}

	private static double weight(CommandContext<CommandSourceStack> c) {
		return DoubleArgumentType.getDouble(c, "weight");
	}

	private static int index(CommandContext<CommandSourceStack> c) {
		return IntegerArgumentType.getInteger(c, "number") - 1;
	}

	private static Crate crate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String id = StringArgumentType.getString(c, "crate");
		return MejtaCrates.service().store().crate(id).orElseThrow(() -> new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
				Component.literal("No crate with the ID " + id + "; see /crates admin list.")).create());
	}

	private static CompletableFuture<Suggestions> suggestCrates(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
		CrateService service = MejtaCrates.serviceOrNull();
		return service == null ? b.buildFuture() : suggest(b, service.store().crates().stream().map(Crate::id).toList());
	}

	private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder b, List<String> options) {
		String typed = b.getRemaining().toLowerCase(Locale.ROOT);
		for (String option : options) {
			if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
				b.suggest(option);
			}
		}
		return b.buildFuture();
	}

	private static ItemStack heldItem(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ItemStack hand = c.getSource().getPlayerOrException().getMainHandItem();
		if (hand.isEmpty()) {
			throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Hold the item in your main hand.")).create();
		}
		return hand;
	}

	private static com.google.gson.JsonElement encode(CommandContext<CommandSourceStack> c, ItemStack stack) {
		return ItemJson.encode(c.getSource().getServer().registryAccess(), stack);
	}

	private static Consumer<Component> reply(CommandContext<CommandSourceStack> c) {
		return c.getSource()::sendSystemMessage;
	}

	private static String stripSlash(String command) {
		String text = command.strip();
		return text.startsWith("/") ? text.substring(1) : text;
	}

	static String dimension(ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
