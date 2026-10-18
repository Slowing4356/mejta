package com.mejta.mejtaperf.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.server.Scheduler;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtaperf.MejtaPerf;
import com.mejta.mejtaperf.PerfPermissions;

/**
 * End-to-end tests of MejtaPerf on a dedicated server: the commands are hidden from players without
 * permission; the monitor measures ticks; metrics, entities and reports work; the profiler samples the real
 * server thread and saves a report; an induced lag raises an alert and its recovery. Every check logs
 * {@code [MejtaPerfTest] PASS/FAIL}.
 */
public final class MejtaPerfClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaPerfTest");

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		check("mejtaperf addon active", MejtaCoreAPI.getAddon(MejtaPerf.ID).map(a -> a.state() == AddonState.ACTIVE).orElse(false));
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			TestServerConnection connection = server.connect();
			connection.getClientLevel().waitForChunksRender();
			try {
				chat(ctx, "/mperf");
				check("unauthorized: /mperf is not available to a non-operator", refused(ctx));
				grant(server, true);
				ctx.waitTicks(60); // a few seconds of ticks and metric snapshots
				statusChecks(ctx);
				reportChecks(ctx, server);
				profilerChecks(ctx);
				lagChecks(ctx, server);
			} finally {
				server.runCommand("mejtaadmin config reset " + MejtaPerf.ID + " alerts.mspt_threshold");
				server.runCommand("mejtaadmin config reset " + MejtaPerf.ID + " alerts.sustain_seconds");
				grant(server, false);
				connection.close();
			}
		}
		LOG.info("[MejtaPerfTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaPerf tests failed: " + String.join("; ", failures));
		}
	}

	private void statusChecks(ClientGameTestContext ctx) {
		clearChat(ctx);
		chat(ctx, "/mperf");
		check("status: header", chatContains(ctx, "MejtaPerf: Server performance"));
		check("status: TPS was measured", chatContains(ctx, " TPS ") && !chatHas(ctx, "TPS 5s -"));
		check("status: MSPT line", chatContains(ctx, "MSPT (1 min) mean"));
		check("status: dimensions listed", chatContains(ctx, "overworld:"));
		check("status: memory and CPU", chatContains(ctx, "Memory ") && chatContains(ctx, "CPU process"));

		clearChat(ctx);
		chat(ctx, "/mperf entities");
		check("entities: summary", chatContains(ctx, "MejtaPerf: Entities:"));
		check("entities: the player is counted", chatContains(ctx, "minecraft:player"));

		clearChat(ctx);
		chat(ctx, "/mperf metrics mejtacore");
		check("metrics: MejtaCore's notification queue gauge", chatContains(ctx, "mejtacore:notify.queue.ingame"));
		check("metrics: MejtaCore's scheduler timer", chatContains(ctx, "mejtacore:scheduler.tasks"));
	}

	private void reportChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		clearChat(ctx);
		chat(ctx, "/mperf report");
		check("report: saved", chatContains(ctx, "Report saved to "));
		Optional<Path> report = waitForFile(ctx, "report-");
		check("report: file exists", report.isPresent());
		String text = report.map(MejtaPerfClientGameTest::read).orElse("");
		check("report: ticks, world, Java, metrics and mods", text.contains("Ticks\n") && text.contains("World\n") && text.contains("Java\n")
				&& text.contains("Mejta metrics") && text.contains("mejtaperf "));
		String me = ctx.computeOnClient(mc -> mc.getUser().getName());
		check("report: no player names", !text.contains(me));
	}

	private void profilerChecks(ClientGameTestContext ctx) {
		clearChat(ctx);
		chat(ctx, "/mperf profiler stop");
		check("profiler: stop with nothing running is refused", chatContains(ctx, "No profile is running."));
		chat(ctx, "/mperf profiler start 5");
		check("profiler: started", chatContains(ctx, "Profiling the server thread for up to 5 s"));
		chat(ctx, "/mperf profiler start 5");
		check("profiler: only one run at a time", chatContains(ctx, "A profile is already running"));
		check("profiler: report announced in game", waitUntil(ctx, 600, () -> chatHas(ctx, "Profile saved")));
		String me = ctx.computeOnClient(mc -> mc.getUser().getName());
		check("profiler: the announcement names nobody", chatHas(ctx, "Profile (started from a command) saved")
				&& ctx.computeOnClient(mc -> chatLines(mc).stream().filter(l -> l.contains("Profile saved")).noneMatch(l -> l.contains(me))));
		Optional<Path> profile = waitForFile(ctx, "profile-");
		check("profiler: report file exists", profile.isPresent());
		String text = profile.map(MejtaPerfClientGameTest::read).orElse("");
		check("profiler: report has mods, methods and a tree", text.contains("Time by mod") && text.contains("Hottest methods")
				&& text.contains("Call tree"));
		check("profiler: the server thread was sampled", text.contains("net.minecraft.server.MinecraftServer") && text.contains("[minecraft]"));
		check("profiler: in-tick samples were taken", text.contains("[tick]"));

		// Only slow ticks: a run with a threshold starts, says so, and still saves its report (CI ticks may all be fast).
		clearChat(ctx);
		chat(ctx, "/mperf profiler slow 10 5");
		check("profiler slow: started", chatContains(ctx, "keeping only ticks of 10 ms or more"));
		check("profiler slow: report announced", waitUntil(ctx, 600, () -> chatHas(ctx, "Profile saved")));
	}

	private void lagChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		server.runCommand("mejtaadmin config set " + MejtaPerf.ID + " alerts.mspt_threshold 30");
		server.runCommand("mejtaadmin config set " + MejtaPerf.ID + " alerts.sustain_seconds 2");
		ctx.waitTicks(10);
		clearChat(ctx);
		// Every tick does 45 ms of extra work: a steady, artificial lag.
		Scheduler.Task lag = server.computeOnServer(s -> MejtaCoreAPI.scheduler().runEvery(1, () -> busy(45)));
		boolean alerted = waitUntil(ctx, 400, () -> chatHas(ctx, "Server lag:"));
		check("lag: alert after sustained lag", alerted);
		check("lag: alert names possible contributors, not a cause", !alerted || chatHas(ctx, "possible contributors, not a diagnosis"));
		server.runOnServer(s -> lag.cancel());
		check("lag: recovery reported", waitUntil(ctx, 600, () -> chatHas(ctx, "Server recovered")));
	}

	private static void busy(long millis) {
		long end = System.nanoTime() + millis * 1_000_000L;
		while (System.nanoTime() < end) {
			Thread.onSpinWait();
		}
	}

	// ---------------------------------------------------------------- helpers

	private static Optional<Path> waitForFile(ClientGameTestContext ctx, String prefix) {
		Path dir = MejtaCoreAPI.dataDirectory(MejtaPerf.ID).resolve("reports");
		Optional<Path>[] found = new Optional[] {Optional.empty()};
		waitUntil(ctx, 200, () -> {
			if (!Files.isDirectory(dir)) {
				return false;
			}
			try (Stream<Path> s = Files.list(dir)) {
				found[0] = s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".txt")).findFirst();
			} catch (IOException e) {
				return false;
			}
			return found[0].isPresent();
		});
		return found[0];
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static void grant(TestDedicatedServerContext server, boolean on) {
		ConfigHandle perms = MejtaCore.configs().find("mejtacore.permissions").orElseThrow();
		for (MejtaPermission permission : PerfPermissions.ALL) {
			Setting<PermissionLevel> setting = (Setting<PermissionLevel>) perms.spec().setting(permission.node()).orElseThrow();
			if (on) {
				perms.set(setting, PermissionLevel.ALL);
			} else {
				perms.reset(setting);
			}
		}
		perms.save();
		server.runOnServer(s -> {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				s.getCommands().sendCommands(p);
			}
		});
	}

	private static void chat(ClientGameTestContext ctx, String text) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(text);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(5);
	}

	private static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
	}

	private static boolean chatHas(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text)));
	}

	/** Minecraft's answer to a command the player may not use: unknown at the top level, an incorrect argument below it. */
	private static boolean refused(ClientGameTestContext ctx) {
		boolean found = waitUntil(ctx, 100, () -> chatHas(ctx, "Unknown or incomplete command") || chatHas(ctx, "Incorrect argument for command"));
		if (!found) {
			LOG.error("[MejtaPerfTest] the command was not refused. Chat: {}", ctx.computeOnClient(MejtaPerfClientGameTest::chatLines));
		}
		return found;
	}

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = waitUntil(ctx, 100, () -> chatHas(ctx, text));
		if (!found) {
			LOG.error("[MejtaPerfTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaPerfClientGameTest::chatLines));
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

	private static boolean waitUntil(ClientGameTestContext ctx, int ticks, BooleanSupplier condition) {
		for (int i = 0; i < ticks; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			ctx.waitTick();
		}
		return condition.getAsBoolean();
	}

	private void check(String name, boolean ok) {
		if (ok) {
			passed++;
			LOG.info("[MejtaPerfTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaPerfTest] FAIL {}", name);
		}
	}
}
