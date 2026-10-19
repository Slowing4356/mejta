package com.mejta.mejtaac.command;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtaac.AcConfig;
import com.mejta.mejtaac.AcPermissions;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.alert.AlertPublisher;
import com.mejta.mejtacore.api.MejtaCoreAPI;

/**
 * Server commands under {@code /mejtaadmin mejtaac}:
 * <ul>
 * <li>{@code status}: configuration summary and tracked players ({@code mejtaac.alerts})</li>
 * <li>{@code alerts}: toggle anti-cheat alerts in your chat ({@code mejtaac.alerts})</li>
 * <li>{@code vl <player>}: current violation levels ({@code mejtaac.alerts})</li>
 * <li>{@code history <name> [limit]}: recent violation records, also for offline players ({@code mejtaac.alerts})</li>
 * <li>{@code reset <player>}: clear violation levels ({@code mejtaac.admin})</li>
 * </ul>
 */
public final class AcCommands {
	private AcCommands() {
	}

	public static void register(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		root.then(Commands.literal("status")
						.requires(MejtaCoreAPI.requires(AcPermissions.ALERTS))
						.executes(AcCommands::status))
				.then(Commands.literal("alerts")
						.requires(MejtaCoreAPI.requires(AcPermissions.ALERTS))
						.executes(AcCommands::toggleAlerts))
				.then(Commands.literal("vl")
						.requires(MejtaCoreAPI.requires(AcPermissions.ALERTS))
						.then(Commands.argument("player", EntityArgument.player()).executes(AcCommands::levels)))
				.then(Commands.literal("history")
						.requires(MejtaCoreAPI.requires(AcPermissions.ALERTS))
						.then(Commands.argument("name", StringArgumentType.word())
								.executes(ctx -> history(ctx, 10))
								.then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
										.executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
				.then(Commands.literal("reset")
						.requires(MejtaCoreAPI.requires(AcPermissions.ADMIN))
						.then(Commands.argument("player", EntityArgument.player()).executes(AcCommands::reset)));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		AntiCheat ac = AntiCheat.get().orElseThrow();
		AcConfig cfg = ac.config();
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(Component.literal("MejtaAC status").withStyle(ChatFormatting.GOLD));
		line(source, "Enabled", String.valueOf(cfg.enabled()));
		line(source, "Enforcement", cfg.enforcementMode() + " (min confidence " + cfg.enforcementMinConfidence() + ")");
		line(source, "Mitigation", cfg.mitigation() + (cfg.mitigates() ? " (active)" : ""));
		line(source, "Alerts", cfg.alertsEnabled() + " (min VL " + cfg.alertsMinVl() + ", cooldown " + cfg.alertsCooldownSeconds() + "s)");
		line(source, "Kick notifications", String.valueOf(cfg.alertsEnforcement()));
		line(source, "Log level", cfg.logLevel().name());
		line(source, "Check protocol.invalid_move", cfg.invalidMoveEnabled() + " (kick VL " + cfg.invalidMoveKickVl() + ")");
		line(source, "Check movement.timer", cfg.timerEnabled() + " (kick VL " + cfg.timerKickVl() + ", max ahead " + cfg.timerMaxAheadMs() + " ms)");
		line(source, "Check movement.ground_spoof", cfg.groundSpoofEnabled() + " (kick VL " + cfg.groundSpoofKickVl() + ")");
		line(source, "Check combat.reach", cfg.reachEnabled() + " (kick VL " + cfg.reachKickVl() + ", tolerance " + cfg.reachTolerance() + ")");
		line(source, "Check combat.multi_target", cfg.multiTargetEnabled() + " (kick VL " + cfg.multiTargetKickVl() + ")");
		line(source, "Check movement.fly", cfg.flyEnabled() + " (kick VL " + cfg.flyKickVl() + ")");
		line(source, "Check combat.hitbox", cfg.hitboxEnabled() + " (kick VL " + cfg.hitboxKickVl() + ")");
		line(source, "Check interaction.block_reach", cfg.blockReachEnabled() + " (kick VL " + cfg.blockReachKickVl()
				+ ", tolerance " + cfg.blockReachTolerance() + ")");
		line(source, "Check movement.speed", cfg.speedEnabled() + " (kick VL " + cfg.speedKickVl() + ")");
		line(source, "Check interaction.fast_break", cfg.fastBreakEnabled() + " (kick VL " + cfg.fastBreakKickVl() + ")");
		line(source, "Check movement.jump", cfg.jumpEnabled() + " (kick VL " + cfg.jumpKickVl() + ")");
		line(source, "Check vehicle.boat_fly", cfg.boatFlyEnabled() + " (kick VL " + cfg.boatFlyKickVl() + ")");
		line(source, "Check movement.elytra", cfg.elytraEnabled() + " (kick VL " + cfg.elytraKickVl() + ", tolerance " + cfg.elytraTolerance() + ")");
		line(source, "Check vehicle.horse", cfg.horseEnabled() + " (kick VL " + cfg.horseKickVl() + ", speed tolerance "
				+ cfg.horseSpeedTolerance() + ")");
		line(source, "Players with violations", String.valueOf(ac.violations().trackedPlayers()));
		return 1;
	}

	private static int toggleAlerts(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		var service = MejtaCoreAPI.notifications();
		boolean nowMuted = !service.isMuted(player.getUUID(), AlertPublisher.CHANNEL_ALERT);
		service.setMuted(player.getUUID(), AlertPublisher.CHANNEL_ALERT, nowMuted);
		service.setMuted(player.getUUID(), AlertPublisher.CHANNEL_ENFORCEMENT, nowMuted);
		ctx.getSource().sendSuccess(() -> Component.literal("Anti-cheat alerts " + (nowMuted ? "hidden" : "shown")
				+ " (kept across restarts)."), false);
		return 1;
	}

	private static int levels(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		Map<String, Double> levels = AntiCheat.get().orElseThrow().levels(target.getUUID());
		String name = target.getGameProfile().name();
		if (levels.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal(name + " has no violations."), false);
			return 0;
		}
		ctx.getSource().sendSystemMessage(Component.literal("Violation levels of " + name + ":").withStyle(ChatFormatting.GOLD));
		levels.forEach((check, vl) -> line(ctx.getSource(), check, String.format(java.util.Locale.ROOT, "%.2f", vl)));
		return levels.size();
	}

	private static int history(CommandContext<CommandSourceStack> ctx, int limit) {
		String name = StringArgumentType.getString(ctx, "name");
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		// Reading runs on MejtaCore's I/O thread; replies are sent back on the server thread.
		AntiCheat.get().orElseThrow().records().recentViolations(name, limit).whenComplete((list, error) -> server.execute(() -> {
			if (error != null) {
				source.sendFailure(Component.literal("Could not read violation records: " + error.getMessage()));
			} else {
				sendHistory(source, name, list);
			}
		}));
		return 1;
	}

	private static void sendHistory(CommandSourceStack source, String name, List<JsonObject> records) {
		if (records.isEmpty()) {
			source.sendSystemMessage(Component.literal("No violation records for " + name + ".").withStyle(ChatFormatting.GRAY));
			return;
		}
		source.sendSystemMessage(Component.literal("Recent violations of " + name + " (newest first):").withStyle(ChatFormatting.GOLD));
		for (JsonObject r : records) {
			String text = str(r, "ts") + " " + str(r, "check") + " [" + str(r, "reason") + "] VL " + str(r, "vl")
					+ " " + (r.has("measurements") ? r.get("measurements").toString() : "")
					+ " ping " + str(r, "ping_ms") + "ms";
			source.sendSystemMessage(Component.literal(" " + text).withStyle(ChatFormatting.GRAY));
		}
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		AntiCheat.get().orElseThrow().violations().reset(target.getUUID());
		ctx.getSource().sendSuccess(() -> Component.literal("Reset violation levels of " + target.getGameProfile().name() + "."), true);
		return 1;
	}

	private static String str(JsonObject r, String key) {
		return r.has(key) ? r.get(key).getAsString() : "?";
	}

	private static void line(CommandSourceStack source, String label, String value) {
		source.sendSystemMessage(Component.literal(" " + label + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}
}
