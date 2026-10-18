package com.mejta.mejtaac.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtaac.AcPermissions;
import com.mejta.mejtaac.AcSettings;
import com.mejta.mejtaac.AcSettings.EnforcementMode;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.MejtaAC;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.check.protocol.InvalidMoveCheck;
import com.mejta.mejtaac.check.protocol.MoveData;
import com.mejta.mejtaac.enforce.EnforcementPolicy;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;
import com.mejta.mejtaac.violation.Throttle;
import com.mejta.mejtaac.violation.ViolationTracker;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;

/**
 * Tests MejtaAC with legitimate and suspicious input: first the pure detection, violation and
 * enforcement logic, then end to end with a real client sending normal movement and crafted
 * movement packets to a singleplayer world and a dedicated server. Every check logs
 * {@code [MejtaAcTest] PASS/FAIL}.
 */
public final class MejtaAcClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaAcTest");

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("mejta");

		registrationChecks(configDir);
		detectionLogicChecks();
		violationLogicChecks();
		enforcementLogicChecks();

		// Decay is covered exactly by violationLogicChecks; end-to-end checks run without it so that
		// test pacing (seconds between packets, unlike a real cheat's 20 per second) cannot change results.
		config().set(AcSettings.DECAY_PER_MINUTE, 0.0);
		config().save();

		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			TestServerContext server = world.getServer();
			legitimateMovementChecks(ctx, server);
			cheatDetectionChecks(ctx, server);
			exemptionAndReloadChecks(ctx, server);
			commandChecks(ctx);
			setPermission(AcPermissions.ALERTS, null);
		}

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			// Both connections end with the server disconnecting the client (a kick, then vanilla's NaN
			// handling), so they are not closed: TestServerConnection.close() only disconnects a client
			// that is still connected, and asserts that it is.
			TestServerConnection kicked = server.connect();
			kicked.getClientLevel().waitForChunksRender();
			enforcementKickChecks(ctx, server);

			TestServerConnection nan = server.connect();
			nan.getClientLevel().waitForChunksRender();
			nanChecks(ctx);
			ctx.setScreen(TitleScreen::new);
		}

		LOG.info("[MejtaAcTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaAC tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- without a world

	private void registrationChecks(Path configDir) {
		check("mejtaac addon active", MejtaCoreAPI.getAddon(MejtaAC.ID).map(a -> a.state() == AddonState.ACTIVE).orElse(false));
		check("anti-cheat installed", AntiCheat.get().isPresent());
		String config = read(configDir.resolve("mejtaac.json"));
		check("mejtaac.json written with defaults", config.contains("\"enforcement.mode\": \"DRY_RUN\"")
				&& config.contains("\"checks.protocol.invalid_move.enabled\": true"));
		String permissions = read(configDir.resolve("mejtacore.permissions.json"));
		check("permission nodes registered with MejtaCore", permissions.contains("\"mejtaac.alerts\": \"MODERATORS\"")
				&& permissions.contains("\"mejtaac.bypass\": \"OWNERS\""));
	}

	private void detectionLogicChecks() {
		InvalidMoveCheck check = new InvalidMoveCheck();
		// Legitimate input: none of these may flag.
		check("legit: normal position", check.evaluate(MoveData.position(123.5, 64, -987.25)).isEmpty());
		check("legit: near world border", check.evaluate(MoveData.position(29_999_999.9, 319, -29_999_999.9)).isEmpty());
		check("legit: pitch exactly +90/-90", check.evaluate(MoveData.rotation(0, 90)).isEmpty()
				&& check.evaluate(MoveData.rotation(0, -90)).isEmpty());
		check("legit: huge unwrapped yaw", check.evaluate(MoveData.rotation(123_456.7F, 10)).isEmpty()
				&& check.evaluate(MoveData.rotation(-98_765.4F, -10)).isEmpty());
		check("legit: position+rotation", check.evaluate(MoveData.both(10, 70, 10, 725, 45)).isEmpty());
		check("legit: absent fields ignored even if garbage",
				check.evaluate(new MoveData(false, Double.NaN, 0, 0, false, Float.NaN, 500)).isEmpty());

		// Suspicious input: each must flag with the right reason and CERTAIN confidence.
		expectReason(check, MoveData.rotation(0, 90.5F), "impossible_pitch");
		expectReason(check, MoveData.rotation(0, -180), "impossible_pitch");
		expectReason(check, MoveData.rotation(Float.NaN, 0), "non_finite_rotation");
		expectReason(check, MoveData.rotation(0, Float.POSITIVE_INFINITY), "non_finite_rotation");
		expectReason(check, MoveData.position(Double.NaN, 64, 0), "non_finite_position");
		expectReason(check, MoveData.position(0, Double.NEGATIVE_INFINITY, 0), "non_finite_position");
		expectReason(check, MoveData.position(30_000_001, 64, 0), "position_out_of_bounds");
		expectReason(check, MoveData.position(0, 20_000_001, 0), "position_out_of_bounds");
		Optional<Flag> flag = check.evaluate(MoveData.rotation(5, 120));
		check("flag carries measurements", flag.isPresent() && Float.valueOf(120).equals(flag.get().measurements().get("pitch")));
		check("flag confidence CERTAIN", flag.isPresent() && flag.get().confidence() == Confidence.CERTAIN);
	}

	private void violationLogicChecks() {
		ViolationTracker tracker = new ViolationTracker();
		UUID p = UUID.randomUUID();
		tracker.add(p, "c", 3, 60, 0);
		check("VL accumulates", tracker.add(p, "c", 1, 60, 0) == 4.0);
		check("VL decays linearly (60/min: -1 per second)", Math.abs(tracker.get(p, "c", 60, 1_000) - 3.0) < 1e-9);
		check("VL never below zero", tracker.get(p, "c", 60, 60_000) == 0.0);
		check("decayed checks hidden from snapshot", tracker.snapshot(p, 60, 60_000).isEmpty());
		check("decay 0 keeps VL", tracker.get(p, "c", 0, 1_000_000) == 4.0);
		tracker.reset(p);
		check("reset clears player", tracker.get(p, "c", 0, 0) == 0.0 && tracker.trackedPlayers() == 0);

		Throttle throttle = new Throttle();
		check("throttle passes first event", throttle.tryPass(p, "k", 1_000, 0) == 0);
		check("throttle suppresses within interval", throttle.tryPass(p, "k", 1_000, 500) == -1
				&& throttle.tryPass(p, "k", 1_000, 900) == -1);
		check("throttle reports suppressed count", throttle.tryPass(p, "k", 1_000, 1_000) == 2);
	}

	private void enforcementLogicChecks() {
		Flag certain = new Flag("c", "r", Confidence.CERTAIN, 1, Map.of());
		Flag low = new Flag("c", "r", Confidence.LOW, 1, Map.of());
		check("below threshold: no action",
				EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, certain, 4.9, 5) == Action.NONE);
		check("ENFORCE at threshold: kick",
				EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, certain, 5, 5) == Action.KICK);
		check("DRY_RUN at threshold: would kick",
				EnforcementPolicy.decide(EnforcementMode.DRY_RUN, Confidence.HIGH, certain, 5, 5) == Action.WOULD_KICK);
		check("OFF never acts", EnforcementPolicy.decide(EnforcementMode.OFF, Confidence.LOW, certain, 1_000, 5) == Action.NONE);
		check("low-confidence flag never enforced, whatever the VL",
				EnforcementPolicy.decide(EnforcementMode.ENFORCE, Confidence.HIGH, low, 1_000, 5) == Action.NONE);
	}

	// ---------------------------------------------------------------- singleplayer

	private void legitimateMovementChecks(ClientGameTestContext ctx, TestServerContext server) {
		UUID id = playerId(ctx);
		ctx.getInput().holdKeyFor(options -> options.keyUp, 30);
		ctx.getInput().holdKeyFor(options -> options.keyJump, 10);
		for (int i = 0; i < 8; i++) {
			// Turn more than two full circles (yaw keeps growing past 720) and look straight up/down.
			ctx.runOnClient(mc -> {
				mc.player.setYRot(mc.player.getYRot() + 100);
				mc.player.setXRot(mc.player.getXRot() > 0 ? -90 : 90);
			});
			ctx.waitTicks(2);
		}
		ctx.getInput().holdKeyFor(options -> options.keyUp, 20);
		check("legitimate walking, jumping and turning not flagged", levels(server, id).isEmpty());
	}

	private void cheatDetectionChecks(ClientGameTestContext ctx, TestServerContext server) {
		UUID id = playerId(ctx);
		String name = playerName(ctx);
		setPermission(AcPermissions.ALERTS, PermissionLevel.ALL);
		clearChat(ctx);

		sendPitch(ctx, 120, 3);
		check("impossible pitch flagged 3 times (VL 3)", waitUntil(ctx, () -> {
			Double vl = levels(server, id).get(InvalidMoveCheck.ID);
			return vl != null && vl > 2.9 && vl <= 3.0;
		}));
		check("alert shown to staff in chat", waitUntil(ctx, () -> chatHas(ctx, name + " failed protocol.invalid_move")));
		check("only one alert within the cooldown", chatCount(ctx, name + " failed protocol.invalid_move") == 1);

		List<JsonObject> records = violations(name);
		check("violation record written", !records.isEmpty() && "impossible_pitch".equals(str(records.get(0), "reason")));
		check("record has measurements, ping and world", !records.isEmpty()
				&& records.get(0).getAsJsonObject("measurements").get("pitch").getAsDouble() == 120.0
				&& records.get(0).has("ping_ms") && str(records.get(0), "world").equals("minecraft:overworld"));
		check("records are rate-limited (1/s per check)", records.size() == 1);
		check("record contains no IP address", records.stream().noneMatch(r -> r.toString().contains("127.0.0.1")));

		sendPitch(ctx, 120, 2);
		check("DRY_RUN: player not kicked at threshold", ctx.computeOnClient(mc -> mc.player != null && mc.getConnection() != null));
		List<JsonObject> enforcement = await(AntiCheat.get().orElseThrow().records().enforcementLog().readRecent(5, r -> true));
		check("DRY_RUN: would-kick audit record", enforcement != null && !enforcement.isEmpty()
				&& "WOULD_KICK".equals(str(enforcement.get(0), "action")) && "DRY_RUN".equals(str(enforcement.get(0), "mode")));
		check("dry-run enforcement alert shown", waitUntil(ctx, () -> chatHas(ctx, name + " would be kicked (dry run)")));
		check("VL reset after enforcement decision", !levels(server, id).containsKey(InvalidMoveCheck.ID));

		// Every notification also lands in MejtaCore's notification file log (works without Discord).
		com.mejta.mejtacore.api.storage.RecordLog fileLog = MejtaCoreAPI.recordLog("mejtacore", "notifications",
				com.mejta.mejtacore.api.storage.RecordLogOptions.DEFAULT);
		check("kick notification in the core notification file log, with what a ban needs", waitUntil(ctx, () -> {
			List<JsonObject> logged = await(fileLog.readRecent(50, r -> "mejtaac.enforcement".equals(str(r, "channel"))));
			if (logged == null || logged.isEmpty()) {
				return false;
			}
			JsonObject fields = logged.get(0).getAsJsonObject("fields");
			return name.equals(str(fields, "player")) && id.toString().equals(str(fields, "uuid"))
					&& "DRY_RUN".equals(str(fields, "mode")) && "protocol.invalid_move".equals(str(fields, "check"));
		}));

		// Kicks are published even when flag alerts are switched off.
		ConfigHandle config = config();
		config.set(AcSettings.ALERTS_ENABLED, false);
		clearChat(ctx);
		sendPitch(ctx, 120, 5);
		check("alerts.enabled off: kick notification still shown",
				waitUntil(ctx, () -> chatHas(ctx, name + " would be kicked (dry run)")));
		check("alerts.enabled off: no flag alert", !chatHas(ctx, name + " failed protocol.invalid_move"));
		config.set(AcSettings.ALERTS_ENFORCEMENT, false);
		clearChat(ctx);
		sendPitch(ctx, 120, 5);
		ctx.waitTicks(20);
		check("alerts.enforcement off: no kick notification", !chatHas(ctx, name + " would be kicked (dry run)"));
		config.reset(AcSettings.ALERTS_ENABLED);
		config.reset(AcSettings.ALERTS_ENFORCEMENT);
		server.runOnServer(s -> AntiCheat.get().orElseThrow().violations().reset(id));
	}

	private void exemptionAndReloadChecks(ClientGameTestContext ctx, TestServerContext server) {
		UUID id = playerId(ctx);
		server.runOnServer(s -> AntiCheat.get().orElseThrow().violations().reset(id));

		setPermission(AcPermissions.BYPASS, PermissionLevel.ALL);
		sendPitch(ctx, 150, 2);
		check("bypass permission exempts player", levels(server, id).isEmpty());

		ConfigHandle config = config();
		config.set(AcSettings.BYPASS_PERMISSION, false);
		sendPitch(ctx, 150, 1);
		check("exemptions.bypass_permission=false ignores bypass", waitUntil(ctx, () -> levels(server, id).containsKey(InvalidMoveCheck.ID)));
		config.reset(AcSettings.BYPASS_PERMISSION);
		setPermission(AcPermissions.BYPASS, null);
		server.runOnServer(s -> AntiCheat.get().orElseThrow().violations().reset(id));

		config.set(AcSettings.INVALID_MOVE_ENABLED, false);
		sendPitch(ctx, 150, 2);
		check("disabling the check live stops flags", levels(server, id).isEmpty());
		config.reset(AcSettings.INVALID_MOVE_ENABLED);

		config.set(AcSettings.ENABLED, false);
		sendPitch(ctx, 150, 1);
		check("master switch off stops flags", levels(server, id).isEmpty());
		config.reset(AcSettings.ENABLED);

		Path file = config.file();
		config.save();
		write(file, read(file).replace("\"checks.protocol.invalid_move.enabled\": true", "\"checks.protocol.invalid_move.enabled\": false"));
		chat(ctx, "/mejta config reload");
		sendPitch(ctx, 150, 1);
		check("config reload from disk applies to active check", levels(server, id).isEmpty());
		config.reset(AcSettings.INVALID_MOVE_ENABLED);
		config.save();
		sendPitch(ctx, 150, 1);
		check("re-enabled check flags again", waitUntil(ctx, () -> levels(server, id).containsKey(InvalidMoveCheck.ID)));
	}

	private void commandChecks(ClientGameTestContext ctx) {
		String name = playerName(ctx);
		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac status");
		check("status command", chatContains(ctx, "MejtaAC status") && chatContains(ctx, "DRY_RUN"));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac vl " + name);
		check("vl command shows level", chatContains(ctx, InvalidMoveCheck.ID));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac history " + name + " 5");
		check("history command reads records", waitUntil(ctx, () -> chatHas(ctx, "impossible_pitch")));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac history nobody_like_this");
		check("history for unknown name", waitUntil(ctx, () -> chatHas(ctx, "No violation records for nobody_like_this")));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac reset " + name);
		check("reset denied without mejtaac.admin", !chatHas(ctx, "Reset violation levels"));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtaac alerts");
		check("alerts toggled off", chatContains(ctx, "alerts hidden"));
		sendPitch(ctx, 170, 1);
		ctx.waitTicks(20);
		check("muted staff gets no alert", !chatHas(ctx, "failed protocol.invalid_move"));
		chat(ctx, "/mejtaadmin mejtaac alerts");
		check("alerts toggled on", chatContains(ctx, "alerts shown"));
	}

	// ---------------------------------------------------------------- dedicated server

	private void enforcementKickChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		String name = playerName(ctx);
		ConfigHandle config = config();
		config.set(AcSettings.DECAY_PER_MINUTE, 0.0);
		config.set(AcSettings.ENFORCEMENT_MODE, EnforcementMode.ENFORCE);
		config.set(AcSettings.KICK_MESSAGE, "MejtaAC test kick");

		sendPitch(ctx, 120, 4);
		check("ENFORCE: not kicked below threshold", ctx.computeOnClient(mc -> mc.getConnection() != null));
		sendPitch(ctx, 120, 1);
		check("ENFORCE: kicked at threshold", waitUntil(ctx,
				() -> ctx.computeOnClient(mc -> mc.screen instanceof DisconnectedScreen)));
		check("kick message shown", ctx.computeOnClient(mc -> disconnectReason(mc).contains("MejtaAC test kick")));
		ctx.takeScreenshot("mejtaac-kicked");

		List<JsonObject> enforcement = await(AntiCheat.get().orElseThrow().records().enforcementLog()
				.readRecent(5, r -> name.equals(str(r, "player"))));
		check("kick audit record", enforcement != null && !enforcement.isEmpty()
				&& "KICK".equals(str(enforcement.get(0), "action")) && "ENFORCE".equals(str(enforcement.get(0), "mode")));
		check("VL state cleaned up after disconnect", waitUntil(ctx,
				() -> server.computeOnServer(s -> AntiCheat.get().orElseThrow().violations().trackedPlayers()) == 0));

		config.resetAll();
		config.save();
	}

	/**
	 * Vanilla disconnects a player who sends a NaN rotation, but logs nothing about who or why.
	 * MejtaAC must record it first and leave vanilla's handling in place.
	 */
	private void nanChecks(ClientGameTestContext ctx) {
		String name = playerName(ctx);
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), Float.NaN, mc.player.onGround(), false)));
		check("vanilla still disconnects on NaN rotation", waitUntil(ctx,
				() -> ctx.computeOnClient(mc -> mc.screen instanceof DisconnectedScreen)));
		check("NaN rotation recorded before vanilla disconnects", violations(name).stream()
				.anyMatch(r -> "non_finite_rotation".equals(str(r, "reason"))));
		check("NaN measurement stored as text", violations(name).stream()
				.filter(r -> "non_finite_rotation".equals(str(r, "reason")))
				.anyMatch(r -> "NaN".equals(r.getAsJsonObject("measurements").get("pitch").getAsString())));
	}

	// ---------------------------------------------------------------- helpers

	private static void sendPitch(ClientGameTestContext ctx, float pitch, int times) {
		for (int i = 0; i < times; i++) {
			ctx.runOnClient(mc -> mc.getConnection().send(
					new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), pitch, mc.player.onGround(), false)));
			ctx.waitTicks(2);
		}
		ctx.waitTicks(2);
	}

	private static Map<String, Double> levels(TestServerContext server, UUID id) {
		return server.computeOnServer(s -> AntiCheat.get().orElseThrow().levels(id));
	}

	private static List<JsonObject> violations(String name) {
		List<JsonObject> list = await(AntiCheat.get().orElseThrow().records().recentViolations(name, 50));
		return list == null ? List.of() : list;
	}

	private static ConfigHandle config() {
		return MejtaCoreAPI.getAddon(MejtaAC.ID).flatMap(a -> a.config()).orElseThrow();
	}

	/** Overrides a permission's required level; {@code null} restores the default. */
	@SuppressWarnings("unchecked")
	private static void setPermission(com.mejta.mejtacore.api.permission.MejtaPermission permission, PermissionLevel level) {
		ConfigHandle perms = com.mejta.mejtacore.core.MejtaCore.configs().find("mejtacore.permissions").orElseThrow();
		Setting<PermissionLevel> setting = (Setting<PermissionLevel>) perms.spec().setting(permission.node()).orElseThrow();
		if (level == null) {
			perms.reset(setting);
		} else {
			perms.set(setting, level);
		}
		perms.save();
	}

	private static UUID playerId(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.getUUID());
	}

	private static String playerName(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
	}

	private static String disconnectReason(Minecraft mc) {
		if (!(mc.screen instanceof DisconnectedScreen screen)) {
			return "";
		}
		try {
			Field field = DisconnectedScreen.class.getDeclaredField("details");
			field.setAccessible(true);
			return ((DisconnectionDetails) field.get(screen)).reason().getString();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read disconnect reason", e);
		}
	}

	private void check(String name, boolean ok) {
		if (ok) {
			passed++;
			LOG.info("[MejtaAcTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaAcTest] FAIL {}", name);
		}
	}

	private void expectReason(InvalidMoveCheck check, MoveData move, String reason) {
		Optional<Flag> flag = check.evaluate(move);
		check("suspicious: " + move + " -> " + reason, flag.isPresent() && flag.get().reason().equals(reason));
	}

	private static void chat(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(command);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(3);
	}

	private static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
	}

	private static boolean chatHas(ClientGameTestContext ctx, String text) {
		return chatCount(ctx, text) > 0;
	}

	private static int chatCount(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> (int) chatLines(mc).stream().filter(line -> line.contains(text)).count());
	}

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = chatHas(ctx, text);
		if (!found) {
			LOG.error("[MejtaAcTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaAcClientGameTest::chatLines));
		}
		return found;
	}

	@SuppressWarnings("unchecked")
	private static List<String> chatLines(Minecraft mc) {
		try {
			Field field = ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			List<GuiMessage> messages = (List<GuiMessage>) field.get(mc.gui.getChat());
			return messages.stream().map(m -> m.content().getString()).toList();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read chat", e);
		}
	}

	private static boolean waitUntil(ClientGameTestContext ctx, BooleanSupplier condition) {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			ctx.waitTick();
		}
		return condition.getAsBoolean();
	}

	private static <T> T await(java.util.concurrent.CompletableFuture<T> future) {
		try {
			return future.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			LOG.error("[MejtaAcTest] future failed", e);
			return null;
		}
	}

	private static String str(JsonObject r, String key) {
		return r.has(key) ? r.get(key).getAsString() : "";
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			return "";
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.writeString(file, content);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
