package com.mejta.mejtaeconomy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.economy.EconomyService;
import com.mejta.mejtacore.api.server.ServerCommandProvider;
import com.mejta.mejtaeconomy.data.Ledger;

/**
 * {@code /balance} ({@code /bal}), {@code /pay}, {@code /baltop} and {@code /eco} (history; staff:
 * give, take, set, reset). Every change goes through MejtaCore's economy service, so listeners and the
 * transaction log see commands like any other change.
 */
public final class EconomyCommands {
	static final String SOURCE = MejtaEconomy.ID;
	private static final SimpleCommandExceptionType ONE_PLAYER = new SimpleCommandExceptionType(Component.literal("Name exactly one player"));
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private static final int HISTORY_LINES = 10;

	private enum AdminAction {
		GIVE, TAKE, SET
	}

	private EconomyCommands() {
	}

	public static void register() {
		ServerCommandProvider balance = (root, ctx) -> root
				.requires(MejtaCoreAPI.requires(EconomyPermissions.BALANCE))
				.executes(c -> balanceOf(c.getSource(), self(c.getSource())))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.requires(MejtaCoreAPI.requires(EconomyPermissions.BALANCE_OTHERS))
						.executes(c -> balanceOf(c.getSource(), profile(c))));
		MejtaCoreAPI.registerRootCommand(SOURCE, "balance", balance);
		MejtaCoreAPI.registerRootCommand(SOURCE, "bal", balance);

		MejtaCoreAPI.registerRootCommand(SOURCE, "pay", (root, ctx) -> root
				.requires(MejtaCoreAPI.requires(EconomyPermissions.PAY))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pay))));

		MejtaCoreAPI.registerRootCommand(SOURCE, "baltop", (root, ctx) -> root
				.requires(MejtaCoreAPI.requires(EconomyPermissions.BALTOP))
				.executes(c -> baltop(c.getSource(), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1, 10_000))
						.executes(c -> baltop(c.getSource(), IntegerArgumentType.getInteger(c, "page")))));

		MejtaCoreAPI.registerRootCommand(SOURCE, "eco", (root, ctx) -> root
				.then(Commands.literal("history")
						.requires(MejtaCoreAPI.requires(EconomyPermissions.HISTORY))
						.executes(c -> {
							NameAndId me = self(c.getSource());
							return history(c.getSource(), me.id(), me.name());
						})
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.requires(MejtaCoreAPI.requires(EconomyPermissions.ADMIN))
								.executes(c -> {
									NameAndId p = profile(c);
									return history(c.getSource(), p.id(), p.name());
								})))
				.then(admin("give", AdminAction.GIVE))
				.then(admin("take", AdminAction.TAKE))
				.then(admin("set", AdminAction.SET))
				.then(Commands.literal("reset")
						.requires(MejtaCoreAPI.requires(EconomyPermissions.ADMIN))
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(c -> adminChange(c, AdminAction.SET, Optional.of(0L)))))
				.then(Commands.literal("status")
						.requires(MejtaCoreAPI.requires(EconomyPermissions.ADMIN))
						.executes(c -> status(c.getSource()))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> admin(String name, AdminAction action) {
		return Commands.literal(name)
				.requires(MejtaCoreAPI.requires(EconomyPermissions.ADMIN))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(c -> adminChange(c, action, Optional.empty()))));
	}

	// ---------------------------------------------------------------- player commands

	private static int balanceOf(CommandSourceStack source, NameAndId who) {
		EconomyService eco = MejtaCoreAPI.economy();
		if (!ready(source)) {
			return 0;
		}
		boolean self = source.getPlayer() != null && source.getPlayer().getUUID().equals(who.id());
		if (!eco.hasAccount(who.id())) {
			source.sendFailure(Component.literal(who.name() + " has no account here."));
			return 0;
		}
		long balance = eco.balance(who.id());
		int rank = MejtaEconomy.provider().ledger().map(l -> l.rank(who.id())).orElse(0);
		source.sendSystemMessage(Component.literal(self ? "Balance: " : who.name() + "'s balance: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(eco.format(balance)).withStyle(ChatFormatting.GOLD))
				.append(Component.literal(rank > 0 ? "  (#" + rank + ")" : "").withStyle(ChatFormatting.DARK_GRAY)));
		return 1;
	}

	private static int pay(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		ServerPlayer payer = source.getPlayerOrException();
		NameAndId target = profile(c);
		EconomyService eco = MejtaCoreAPI.economy();
		if (!ready(source)) {
			return 0;
		}
		if (!cfg(EconomySettings.PAY_ENABLED)) {
			source.sendFailure(Component.literal("Paying other players is turned off."));
			return 0;
		}
		OptionalLong amount = amount(source, StringArgumentType.getString(c, "amount"));
		if (amount.isEmpty()) {
			return 0;
		}
		long min = MejtaEconomy.provider().minor(cfg(EconomySettings.PAY_MIN));
		if (amount.getAsLong() < min) {
			source.sendFailure(Component.literal("You can pay no less than " + eco.format(min) + "."));
			return 0;
		}
		ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.id());
		if (online == null && !cfg(EconomySettings.PAY_OFFLINE)) {
			source.sendFailure(Component.literal(target.name() + " is not online."));
			return 0;
		}
		if (!eco.hasAccount(target.id())) {
			source.sendFailure(Component.literal(target.name() + " has never played here."));
			return 0;
		}
		EconomyResult r = eco.transfer(payer.getUUID(), target.id(), amount.getAsLong(), SOURCE, "/pay " + target.name());
		if (!r.success()) {
			source.sendFailure(Component.literal(r.status() == EconomyResult.Status.INSUFFICIENT_FUNDS
					? "You only have " + eco.format(r.balance()) + "." : r.message()));
			return 0;
		}
		String money = eco.format(amount.getAsLong());
		payer.sendSystemMessage(Component.literal("You paid " + target.name() + " ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(money).withStyle(ChatFormatting.GOLD))
				.append(Component.literal(". Balance: " + eco.format(r.balance())).withStyle(ChatFormatting.GRAY)));
		if (online != null) {
			online.sendSystemMessage(Component.literal(payer.getGameProfile().name() + " paid you ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(money).withStyle(ChatFormatting.GREEN))
					.append(Component.literal(".").withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}

	private static int baltop(CommandSourceStack source, int page) {
		if (!ready(source)) {
			return 0;
		}
		Ledger ledger = MejtaEconomy.provider().ledger().orElseThrow();
		EconomyService eco = MejtaCoreAPI.economy();
		int size = cfg(EconomySettings.BALTOP_PAGE);
		List<Ledger.Account> ranking = ledger.ranking();
		int pages = Math.max(1, (ranking.size() + size - 1) / size);
		int p = Math.min(page, pages);
		source.sendSystemMessage(Component.literal("Richest players (page " + p + "/" + pages + "), " + eco.format(ledger.total()) + " in total")
				.withStyle(ChatFormatting.GOLD));
		for (int i = (p - 1) * size; i < Math.min(ranking.size(), p * size); i++) {
			Ledger.Account a = ranking.get(i);
			source.sendSystemMessage(Component.literal(" " + (i + 1) + ". ").withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(a.name() + " ").withStyle(ChatFormatting.WHITE))
					.append(Component.literal(eco.format(a.balance())).withStyle(ChatFormatting.GOLD)));
		}
		ServerPlayer player = source.getPlayer();
		if (player != null && ledger.has(player.getUUID())) {
			source.sendSystemMessage(Component.literal(" You are #" + ledger.rank(player.getUUID()) + " with "
					+ eco.format(ledger.balance(player.getUUID()))).withStyle(ChatFormatting.GRAY));
		}
		return ranking.size();
	}

	private static int history(CommandSourceStack source, UUID player, String name) {
		if (!cfg(EconomySettings.LOG_TRANSACTIONS)) {
			source.sendFailure(Component.literal("Transactions are not logged (log.transactions is off)."));
			return 0;
		}
		String id = player.toString();
		MinecraftServer server = source.getServer();
		MejtaEconomy.provider().transactions()
				.readRecent(HISTORY_LINES, r -> id.equals(str(r, "player")) || id.equals(str(r, "to")))
				.whenComplete((list, error) -> server.execute(() -> {
					if (error != null) {
						MejtaEconomy.log().warn("Could not read transactions", error);
						source.sendFailure(Component.literal("Could not read the transaction log."));
						return;
					}
					if (list.isEmpty()) {
						source.sendSystemMessage(Component.literal("No transactions for " + name + ".").withStyle(ChatFormatting.GRAY));
						return;
					}
					source.sendSystemMessage(Component.literal(name + "'s last transactions, newest first:").withStyle(ChatFormatting.GOLD));
					for (JsonObject r : list) {
						source.sendSystemMessage(describe(r, id));
					}
				}));
		return 1;
	}

	/** One log record from the view of {@code player}: {@code 10-23 15:02  +$12.00  /pay Steve (from Alex)}. */
	private static Component describe(JsonObject r, String player) {
		EconomyService eco = MejtaCoreAPI.economy();
		String type = str(r, "type");
		long amount = r.has("amount") ? r.get("amount").getAsLong() : 0;
		boolean incoming = switch (type) {
			case "deposit", "open" -> true;
			case "transfer" -> player.equals(str(r, "to"));
			default -> false;
		};
		String time = "";
		try {
			time = TIME.format(Instant.parse(str(r, "ts")));
		} catch (RuntimeException ignored) {
			// no or odd timestamp: leave it out
		}
		String other = "transfer".equals(type) ? (incoming ? " from " + str(r, "name") : " to " + str(r, "to_name")) : "";
		MutableComponent line = Component.literal(time + "  ").withStyle(ChatFormatting.DARK_GRAY);
		if ("open".equals(type)) {
			return line.append(Component.literal("account opened with " + eco.format(amount)).withStyle(ChatFormatting.GRAY));
		}
		return line.append(Component.literal((incoming ? "+" : "-") + eco.format(amount)).withStyle(incoming ? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(Component.literal("  " + str(r, "reason") + other + " [" + str(r, "source") + "]").withStyle(ChatFormatting.GRAY));
	}

	// ---------------------------------------------------------------- staff commands

	private static int adminChange(CommandContext<CommandSourceStack> c, AdminAction action, Optional<Long> fixed) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		NameAndId target = profile(c);
		EconomyService eco = MejtaCoreAPI.economy();
		if (!ready(source)) {
			return 0;
		}
		long amount;
		if (fixed.isPresent()) {
			amount = fixed.get();
		} else {
			String text = StringArgumentType.getString(c, "amount");
			if (action == AdminAction.SET && text.equals("0")) {
				amount = 0;
			} else {
				OptionalLong parsed = amount(source, text);
				if (parsed.isEmpty()) {
					return 0;
				}
				amount = parsed.getAsLong();
			}
		}
		eco.ensureAccount(target.id(), Optional.of(target.name()));
		long before = eco.balance(target.id());
		String reason = "staff " + action.name().toLowerCase(Locale.ROOT) + " by " + source.getTextName();
		EconomyResult r = switch (action) {
			case GIVE -> eco.deposit(target.id(), amount, SOURCE, reason);
			case TAKE -> eco.withdraw(target.id(), amount, SOURCE, reason);
			case SET -> amount == before ? EconomyResult.success(before)
					: amount > before ? eco.deposit(target.id(), amount - before, SOURCE, reason)
					: eco.withdraw(target.id(), before - amount, SOURCE, reason);
		};
		MejtaCoreAPI.audit().record(AuditEntry.builder(SOURCE, "eco." + action.name().toLowerCase(Locale.ROOT))
				.actor(MejtaCoreAPI.actor(source))
				.target(target.id(), target.name())
				.outcome(r.success() ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
				.detail("amount", eco.format(amount))
				.detail("before", eco.format(before))
				.detail("after", eco.format(r.balance()))
				.build());
		if (!r.success()) {
			source.sendFailure(Component.literal(r.status() == EconomyResult.Status.INSUFFICIENT_FUNDS
					? target.name() + " only has " + eco.format(r.balance()) + ". Use /eco set to change it to less." : r.message()));
			return 0;
		}
		source.sendSystemMessage(Component.literal(target.name() + ": " + eco.format(before) + " → ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(eco.format(r.balance())).withStyle(ChatFormatting.GOLD)));
		return 1;
	}

	private static int status(CommandSourceStack source) {
		if (!ready(source)) {
			return 0;
		}
		Ledger ledger = MejtaEconomy.provider().ledger().orElseThrow();
		EconomyService eco = MejtaCoreAPI.economy();
		source.sendSystemMessage(Component.literal("MejtaEconomy: " + ledger.size() + " account(s), " + eco.format(ledger.total())
				+ " in circulation, " + ledger.digits() + " decimal(s)" + (ledger.maxBalance() < Ledger.HARD_MAX
						? ", limit " + eco.format(ledger.maxBalance()) : "")).withStyle(ChatFormatting.GOLD));
		return 1;
	}

	// ---------------------------------------------------------------- helpers

	private static boolean ready(CommandSourceStack source) {
		if (MejtaEconomy.provider().ledger().isEmpty()) {
			source.sendFailure(Component.literal("The economy is not loaded; see the server log."));
			return false;
		}
		return true;
	}

	private static OptionalLong amount(CommandSourceStack source, String text) {
		OptionalLong amount = MejtaCoreAPI.economy().parse(text);
		if (amount.isEmpty()) {
			int digits = MejtaCoreAPI.economy().fractionDigits();
			source.sendFailure(Component.literal("'" + text + "' is not an amount. Use a positive number"
					+ (digits > 0 ? " with at most " + digits + " decimal(s)" : " without decimals") + ", e.g. 25, 2.5k."));
		}
		return amount;
	}

	private static NameAndId self(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return new NameAndId(player.getUUID(), player.getGameProfile().name());
	}

	private static NameAndId profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(c, "player");
		if (profiles.size() != 1) {
			throw ONE_PLAYER.create();
		}
		return profiles.iterator().next();
	}

	private static String str(JsonObject r, String key) {
		return r.has(key) && r.get(key).isJsonPrimitive() ? r.get(key).getAsString() : "";
	}

	private static <T> T cfg(Setting<T> setting) {
		return MejtaEconomy.config().get(setting);
	}
}
