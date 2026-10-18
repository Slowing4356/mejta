package com.mejta.mejtaac.test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.AcSettings;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.MejtaAC;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.check.combat.HitboxCheck;
import com.mejta.mejtaac.check.combat.MultiTargetCheck;
import com.mejta.mejtaac.check.combat.ReachCheck;
import com.mejta.mejtaac.check.interaction.BlockReachCheck;
import com.mejta.mejtaac.check.interaction.FastBreakCheck;
import com.mejta.mejtaac.check.movement.FlyCheck;
import com.mejta.mejtaac.check.movement.GroundSpoofCheck;
import com.mejta.mejtaac.check.movement.SpeedCheck;
import com.mejta.mejtaac.check.movement.TimerCheck;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Stage 4: timer, ground spoof, fly, speed, reach, hitbox, multi-target, block reach and fast break,
 * tested against latency.
 *
 * <p>A real client plays on an in-process dedicated server (as a non-op) through
 * {@link LatencySimulator} at several network profiles. For each profile it checks that legitimate
 * play produces <b>zero</b> flags (idle, walking, jumping, a network freeze, attacking a target that
 * moves away from the player, rapid clicking, the floor disappearing) and measures how quickly
 * crafted cheats are detected. Results are logged and written to {@code mejtaac-stage4-latency.txt}.
 */
public final class MejtaAcStage4GameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaAcTest");

	/** Round-trip latency added by the simulator, its jitter, and whether a 1.5 s freeze happens. */
	private record Profile(String name, int rttMs, int jitterMs, boolean freeze) {
	}

	private static final List<Profile> PROFILES = List.of(
			new Profile("0 ms", 0, 0, false),
			new Profile("150 ms", 150, 0, false),
			new Profile("300 ms", 300, 0, false),
			new Profile("500 ms", 500, 0, false),
			new Profile("300 ms +-80 jitter + 1.5 s freeze", 300, 80, true));

	private record FlagEvent(long nanos, String check, String reason, Confidence confidence) {
	}

	private final List<String> failures = new ArrayList<>();
	private final List<String> table = new ArrayList<>();
	private final List<FlagEvent> flags = new CopyOnWriteArrayList<>();
	private int passed;

	// Server-side mover for the legitimate moving-target scenario.
	private static volatile ArmorStand moverStand;
	private static volatile Vec3 moverOrigin;
	private static volatile double moverDistance;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		pureLogicChecks();

		ConfigHandle config = config();
		config.set(AcSettings.DECAY_PER_MINUTE, 0.0);
		config.set(AcSettings.LOG_LEVEL, AcSettings.LogLevel.VERBOSE);
		config.save();
		AntiCheat.get().orElseThrow().addFlagListener(d ->
				flags.add(new FlagEvent(System.nanoTime(), d.flag().checkId(), d.flag().reason(), d.flag().confidence())));
		ServerTickEvents.END_SERVER_TICK.register(server -> moveStand());

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("difficulty peaceful");
			try (TestServerConnection connection = server.connect()) {
				connection.getClientLevel().waitForChunksRender();
				Arena arena = buildArena(ctx, server);
				LatencySimulator simulator = ctx.computeOnClient(LatencySimulator::install);
				for (Profile profile : PROFILES) {
					runProfile(ctx, server, arena, simulator, profile);
				}
				simulator.set(0, 0);
				ctx.waitTicks(10);
			}
		}

		config.resetAll();
		config.save();
		writeTable();
		LOG.info("[MejtaAcTest] STAGE4 SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaAC Stage 4 tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- pure logic, exact inputs

	private void pureLogicChecks() {
		long ms = 1_000_000L;

		TimerCheck.State steady = new TimerCheck.State();
		boolean flagged = false;
		for (int i = 0; i <= 1200; i++) {
			flagged |= TimerCheck.onClientTick(steady, i * 50 * ms, 50, 500).isPresent();
		}
		check("timer: steady 20 ticks/s for 60 s never flags", !flagged);

		TimerCheck.State jitter = new TimerCheck.State();
		java.util.Random random = new java.util.Random(1);
		long t = 0;
		flagged = false;
		for (int i = 0; i < 1200; i++) {
			long arrival = Math.max(t, i * 50 * ms + (long) ((random.nextDouble() * 160 - 80) * ms));
			t = arrival;
			flagged |= TimerCheck.onClientTick(jitter, arrival, 50, 500).isPresent();
		}
		check("timer: +-80 ms jitter for 60 s never flags", !flagged);

		TimerCheck.State freeze = new TimerCheck.State();
		flagged = false;
		for (int i = 0; i < 100; i++) {
			flagged |= TimerCheck.onClientTick(freeze, i * 50 * ms, 50, 500).isPresent();
		}
		long resume = 99 * 50 * ms + 3000 * ms;
		for (int i = 0; i < 60; i++) { // 3 s of missed ticks arrive at once
			flagged |= TimerCheck.onClientTick(freeze, resume + i * ms / 10, 50, 500).isPresent();
		}
		for (int i = 1; i < 200; i++) {
			flagged |= TimerCheck.onClientTick(freeze, resume + i * 50 * ms, 50, 500).isPresent();
		}
		check("timer: 3 s freeze followed by a catch-up burst never flags", !flagged);

		TimerCheck.State fast = new TimerCheck.State();
		int flaggedAt = -1;
		for (int i = 0; i < 200 && flaggedAt < 0; i++) {
			if (TimerCheck.onClientTick(fast, i * 25 * ms, 50, 500).isPresent()) {
				flaggedAt = i;
			}
		}
		check("timer: 2x speed flagged once 1.5 s of debt builds up (tick " + flaggedAt + ")", flaggedAt >= 58 && flaggedAt <= 62);

		TimerCheck.State banking = new TimerCheck.State();
		long time = 0;
		for (int i = 0; i < 100; i++) { // half speed: credit stays capped
			time += 100 * ms;
			TimerCheck.onClientTick(banking, time, 50, 500);
		}
		flaggedAt = -1;
		for (int i = 0; i < 200 && flaggedAt < 0; i++) {
			time += 25 * ms;
			if (TimerCheck.onClientTick(banking, time, 50, 500).isPresent()) {
				flaggedAt = i;
			}
		}
		check("timer: slow play cannot bank credit for a later speed-up (flag at tick " + flaggedAt + ")", flaggedAt > 0 && flaggedAt <= 62);

		check("ground: required ticks scale with RTT", GroundSpoofCheck.requiredTicks(0) == 6
				&& GroundSpoofCheck.requiredTicks(500) == 16 && GroundSpoofCheck.requiredTicks(-1) == 26);
		GroundSpoofCheck.State ground = new GroundSpoofCheck.State();
		boolean early = false;
		for (int i = 0; i < 15; i++) {
			early |= GroundSpoofCheck.onClientTick(ground, true, false, false, 500, 100).isPresent();
		}
		check("ground: no flag before the RTT allowance", !early);
		check("ground: flag once the claim outlasts it", GroundSpoofCheck.onClientTick(ground, true, false, false, 500, 100).isPresent());
		GroundSpoofCheck.State reset = new GroundSpoofCheck.State();
		boolean any = false;
		for (int i = 0; i < 100; i++) {
			any |= GroundSpoofCheck.onClientTick(reset, true, i % 10 == 0, false, 0, 100).isPresent()
					| GroundSpoofCheck.onClientTick(reset, true, false, true, 0, 100).isPresent();
		}
		check("ground: real ground or an exemption resets the streak", !any);

		MultiTargetCheck.State multi = new MultiTargetCheck.State();
		boolean sameTarget = MultiTargetCheck.onAttack(multi, 7).isEmpty() && MultiTargetCheck.onAttack(multi, 7).isEmpty()
				&& MultiTargetCheck.onAttack(multi, 7).isEmpty();
		check("multi_target: repeated attacks on one entity in a tick allowed", sameTarget);
		Optional<Flag> second = MultiTargetCheck.onAttack(multi, 8);
		check("multi_target: second entity in the same tick flagged CERTAIN",
				second.isPresent() && second.get().confidence() == Confidence.CERTAIN);
		MultiTargetCheck.onClientTickEnd(multi);
		check("multi_target: next tick may attack another entity", MultiTargetCheck.onAttack(multi, 9).isEmpty());

		Vec3 eye = new Vec3(0, 1.62, 0);
		AABB at = box(3.0);
		check("reach: exactly at the limit not flagged", ReachCheck.evaluate(List.of(eye), List.of(at), 3.0, 0.1, true, 0).isEmpty());
		Optional<Flag> medium = ReachCheck.evaluate(List.of(eye), List.of(box(3.3)), 3.0, 0.1, true, 0);
		check("reach: 0.3 over -> MEDIUM", medium.isPresent() && medium.get().confidence() == Confidence.MEDIUM);
		Optional<Flag> high = ReachCheck.evaluate(List.of(eye), List.of(box(4.0)), 3.0, 0.1, true, 0);
		check("reach: 1.0 over -> HIGH", high.isPresent() && high.get().confidence() == Confidence.HIGH);
		Optional<Flag> unsynced = ReachCheck.evaluate(List.of(eye), List.of(box(4.0)), 3.0, 0.1, false, -1);
		check("reach: unsynced never above MEDIUM", unsynced.isPresent() && unsynced.get().confidence() == Confidence.MEDIUM);
		check("reach: nearest of several positions is used",
				ReachCheck.evaluate(List.of(eye), List.of(box(6.0), box(2.0)), 3.0, 0.1, true, 0).isEmpty());
		// Target passing the player sideways: 5 blocks to each side of the closest point, which is
		// only 2 away. Both recorded endpoints are far, the path between them is not.
		AABB left = new AABB(-5.25, 0, 1.75, -4.75, 2, 2.25);
		AABB right = new AABB(4.75, 0, 1.75, 5.25, 2, 2.25);
		check("reach: interpolated path between positions counts (sweep)",
				ReachCheck.evaluate(List.of(eye), ReachCheck.sweep(List.of(left, right)), 3.0, 0.1, true, 0).isEmpty()
						&& ReachCheck.evaluate(List.of(eye), List.of(left, right), 3.0, 0.1, true, 0).isPresent());
		check("reach: window covers update interval + interpolation", ReachCheck.windowStart(100, 3) == 92);

		HitboxCheck.Rotation lookX = new HitboxCheck.Rotation(-90, 0); // yaw -90 looks towards +X
		HitboxCheck.Rotation lookBack = new HitboxCheck.Rotation(90, 0);
		check("hitbox: looking at the target passes",
				HitboxCheck.evaluate(List.of(eye), List.of(lookX), List.of(box(2.5)), 4, true, 0).isEmpty());
		Optional<Flag> away = HitboxCheck.evaluate(List.of(eye), List.of(lookBack), List.of(box(2.5)), 4, true, 0);
		check("hitbox: looking away flagged HIGH", away.isPresent() && away.get().confidence() == Confidence.HIGH);
		check("hitbox: either candidate rotation may hit",
				HitboxCheck.evaluate(List.of(eye), List.of(lookBack, lookX), List.of(box(2.5)), 4, true, 0).isEmpty());
		HitboxCheck.Rotation slightlyOff = new HitboxCheck.Rotation(-90 + 8, 0); // edge of a 0.5-wide box at 2.5 blocks
		check("hitbox: grazing the edge passes",
				HitboxCheck.evaluate(List.of(eye), List.of(slightlyOff), List.of(box(2.5)), 4, true, 0).isEmpty());
		Optional<Flag> unsyncedAway = HitboxCheck.evaluate(List.of(eye), List.of(lookBack), List.of(box(2.5)), 4, false, -1);
		check("hitbox: unsynced never above MEDIUM", unsyncedAway.isPresent() && unsyncedAway.get().confidence() == Confidence.MEDIUM);

		AABB block = new AABB(4, 1, 0, 5, 2, 1);
		Vec3 blockEye = new Vec3(0, 1.62, 0.5);
		check("block reach: 4.0 within 4.5 range", BlockReachCheck.evaluate(List.of(blockEye), block, 4.5, 0.1, "x").isEmpty());
		Optional<Flag> blockMedium = BlockReachCheck.evaluate(List.of(blockEye), block.move(0.8, 0, 0), 4.5, 0.1, "x");
		check("block reach: 0.3 over -> MEDIUM", blockMedium.isPresent() && blockMedium.get().confidence() == Confidence.MEDIUM);
		Optional<Flag> blockHigh = BlockReachCheck.evaluate(List.of(blockEye), block.move(2, 0, 0), 4.5, 0.1, "x");
		check("block reach: 1.5 over -> HIGH", blockHigh.isPresent() && blockHigh.get().confidence() == Confidence.HIGH);

		FlyCheck.State fall = new FlyCheck.State();
		double vy = 0.42; // a jump
		boolean fallFlagged = false;
		for (int i = 0; i < 60; i++) {
			fallFlagged |= FlyCheck.onClientTick(fall, vy, 0.08, false).isPresent();
			vy = (vy - 0.08) * 0.98;
		}
		check("fly: a real jump and 3 s fall never flag", !fallFlagged);
		FlyCheck.State hover = new FlyCheck.State();
		Flag firstHover = null;
		Flag highHover = null;
		for (int i = 0; i < 40; i++) {
			Optional<Flag> f = FlyCheck.onClientTick(hover, 0.0, 0.08, false);
			if (f.isPresent() && firstHover == null) {
				firstHover = f.get();
			}
			if (f.isPresent() && f.get().confidence() == Confidence.HIGH && highHover == null) {
				highHover = f.get();
			}
		}
		check("fly: hovering flagged (MEDIUM first, HIGH when sustained)", firstHover != null && "hovering".equals(firstHover.reason())
				&& firstHover.confidence() == Confidence.MEDIUM && highHover != null);
		FlyCheck.State glide = new FlyCheck.State();
		boolean glideFlagged = false;
		for (int i = 0; i < 40; i++) {
			glideFlagged |= FlyCheck.onClientTick(glide, -0.05, 0.08, false).map(f -> f.reason().equals("falling_too_slowly")).orElse(false);
		}
		check("fly: gliding flagged", glideFlagged);
		FlyCheck.State unloaded = new FlyCheck.State();
		boolean unloadedFlagged = false;
		for (int i = 0; i < 60; i++) {
			unloadedFlagged |= FlyCheck.onClientTick(unloaded, -0.098, 0.08, false).isPresent();
		}
		check("fly: constant -0.098 (chunk not loaded on the client) not flagged", !unloadedFlagged);
		FlyCheck.State bounce = new FlyCheck.State();
		boolean bounceFlagged = false;
		double v = -1.2;
		for (int i = 0; i < 10; i++) { // falling
			bounceFlagged |= FlyCheck.onClientTick(bounce, v, 0.08, false).isPresent();
			v = (v - 0.08) * 0.98;
		}
		bounceFlagged |= FlyCheck.onClientTick(bounce, 0, 0.08, true).isPresent(); // landing tick: untrusted
		v = 1.5; // slime bounce
		for (int i = 0; i < 30; i++) {
			bounceFlagged |= FlyCheck.onClientTick(bounce, v, 0.08, false).isPresent();
			v = (v - 0.08) * 0.98;
		}
		check("fly: a bounce after a landing is not flagged", !bounceFlagged);

		// Sprinting on normal ground: v = v * 0.546 + 0.13, converging on 0.286 blocks/tick.
		SpeedCheck.State sprint = new SpeedCheck.State();
		double v2 = 0;
		boolean sprintFlagged = false;
		for (int i = 0; i < 100; i++) {
			v2 = v2 * 0.546 + 0.13;
			sprintFlagged |= SpeedCheck.onClientTick(sprint, v2, 0.546, 0.13, false, false).isPresent();
		}
		check("speed: sprinting on ground never flags", !sprintFlagged);
		check("speed: sprint-jump boost allowed once on take-off", SpeedCheck.bound(0.286, 0.91, 0.026, true) > 0.286 + 0.2);
		SpeedCheck.State hack = new SpeedCheck.State();
		Flag firstSpeed = null;
		Flag highSpeed = null;
		for (int i = 0; i < 40; i++) {
			Optional<Flag> f = SpeedCheck.onClientTick(hack, 0.5, 0.546, 0.13, false, false);
			if (f.isPresent() && firstSpeed == null) {
				firstSpeed = f.get();
			}
			if (f.isPresent() && f.get().confidence() == Confidence.HIGH && highSpeed == null) {
				highSpeed = f.get();
			}
		}
		check("speed: 0.5 blocks/tick on ground flagged, HIGH when sustained", firstSpeed != null && highSpeed != null);
		SpeedCheck.State impulse = new SpeedCheck.State();
		SpeedCheck.onClientTick(impulse, 0.2, 0.546, 0.13, false, false);
		boolean impulseFlagged = SpeedCheck.onClientTick(impulse, 1.5, 0.546, 0.13, false, true).isPresent();
		for (double v3 = 1.5; v3 > 0.05; v3 *= 0.91) {
			impulseFlagged |= SpeedCheck.onClientTick(impulse, v3, 0.91, 0.026, false, false).isPresent();
		}
		check("speed: knockback (untrusted tick) then normal decay not flagged", !impulseFlagged);

		check("fast break: expected ticks = ceil(1 / progress)", FastBreakCheck.expectedTicks(1.0 / 15) == 15
				&& FastBreakCheck.expectedTicks(0.3) == 4 && FastBreakCheck.expectedTicks(1.0) == 1);
		check("fast break: on time is fine, 2 ticks of slack",
				FastBreakCheck.onFinish(1.0 / 15, 15).isEmpty() && FastBreakCheck.onFinish(1.0 / 15, 13).isEmpty());
		Optional<Flag> breakMedium = FastBreakCheck.onFinish(1.0 / 15, 11);
		check("fast break: 25 % early -> MEDIUM", breakMedium.isPresent() && breakMedium.get().confidence() == Confidence.MEDIUM);
		Optional<Flag> breakHigh = FastBreakCheck.onFinish(1.0 / 15, 3);
		check("fast break: 80 % early -> HIGH", breakHigh.isPresent() && breakHigh.get().confidence() == Confidence.HIGH);
		check("fast break: instant blocks ignored", FastBreakCheck.onFinish(1.0, 0).isEmpty());
	}

	/** A 0.5-wide, 2-high box whose near face is {@code distance} blocks from the origin along +X. */
	private static AABB box(double distance) {
		return new AABB(distance, 0, -0.25, distance + 0.5, 2, 0.25);
	}

	// ---------------------------------------------------------------- arena

	private record Arena(UUID player, String name, int x, int y, int z) {
		Vec3 feet() {
			return new Vec3(x + 0.5, y + 1, z + 0.5);
		}
	}

	private Arena buildArena(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
		String name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		int x = ctx.computeOnClient(mc -> mc.player.getBlockX());
		int z = ctx.computeOnClient(mc -> mc.player.getBlockZ());
		int y = 150;
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone", x - 8, y, z - 8, x + 8, y, z + 8));
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone", x - 8, y - 4, z - 8, x + 8, y - 4, z + 8));
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", x - 8, y + 1, z - 8, x + 8, y + 12, z + 8));
		Arena arena = new Arena(id, name, x, y, z);
		teleportHome(ctx, server, arena);
		return arena;
	}

	private void teleportHome(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		Vec3 home = arena.feet();
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f 0 0", arena.name(), home.x, home.y, home.z));
		waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(home) < 0.5));
		ctx.waitTicks(10);
	}

	// ---------------------------------------------------------------- one latency profile

	private void runProfile(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim, Profile p) {
		LOG.info("[MejtaAcTest] ===== profile {} =====", p.name());
		sim.set(p.rttMs() / 2, p.jitterMs() / 2);
		ctx.waitTicks(80);
		int rtt = server.computeOnServer(s -> AntiCheat.get().orElseThrow().sync().rttMs(arena.player()));
		check(p.name() + ": measured RTT " + rtt + " ms matches simulated " + p.rttMs() + " ms",
				Math.abs(rtt - p.rttMs()) <= 80 + p.jitterMs());
		resetViolations(server, arena);

		// ---- legitimate play: must not produce a single flag
		int legitStart = flags.size();
		idleWalkJump(ctx, sim, p);
		int legitAttacks = movingTargetAttacks(ctx, server, arena);
		double uncompensated = TestHooks.maxUncompensatedDistance;
		boolean sameTarget = rapidClicks(ctx, server, arena);
		floorDisappears(ctx, server, arena);
		int blockActions = blockInteractions(ctx, server, arena);
		double launchHeight = knockbackLaunch(ctx, server, arena);
		int bounces = slimeBounce(ctx, server, arena);
		double sprintSpeed = sprintJumps(ctx, server, arena, null);
		double potionSpeed = sprintJumps(ctx, server, arena, "speed");
		double iceSpeed = sprintJumps(ctx, server, arena, "ice");
		boolean mined = mineDirt(ctx, server, arena);
		List<FlagEvent> legit = flagsSince(legitStart);
		for (String checkId : List.of(TimerCheck.ID, GroundSpoofCheck.ID, FlyCheck.ID, SpeedCheck.ID, ReachCheck.ID, HitboxCheck.ID,
				MultiTargetCheck.ID, BlockReachCheck.ID, FastBreakCheck.ID)) {
			long count = legit.stream().filter(f -> f.check().equals(checkId)).count();
			check(p.name() + ": legitimate play, no " + checkId + " flags (" + count + ")", count == 0);
		}
		check(p.name() + ": legitimate hits on a target moving away were sent (" + legitAttacks + ")", legitAttacks > 0);
		LOG.info("[MejtaAcTest] {}: farthest server-side distance when a legitimate hit arrived: {} blocks", p.name(),
				String.format(Locale.ROOT, "%.2f", uncompensated));
		if (p.rttMs() >= 300) {
			check(p.name() + ": without compensation a legitimate hit would look like reach ("
					+ String.format(Locale.ROOT, "%.2f", uncompensated) + " blocks)", uncompensated > 3.6);
		}
		check(p.name() + ": vanilla client attacks one entity per tick (multi_target premise)", sameTarget);
		check(p.name() + ": legitimate block break/place actions were sent (" + blockActions + ")", blockActions > 0);
		check(p.name() + String.format(Locale.ROOT, ": knockback really launched the player (%.2f blocks up)", launchHeight),
				launchHeight >= 2.0);
		check(p.name() + ": the player really bounced on slime (" + bounces + " bounces)", bounces >= 1);
		check(p.name() + ": legitimate mining really broke the dirt block", mined);
		check(p.name() + String.format(Locale.ROOT, ": sprint-jumping really reached %.3f blocks/tick", sprintSpeed), sprintSpeed >= 0.25);
		check(p.name() + String.format(Locale.ROOT, ": with Speed II really reached %.3f blocks/tick", potionSpeed), potionSpeed > sprintSpeed);
		check(p.name() + String.format(Locale.ROOT, ": on packed ice really reached %.3f blocks/tick", iceSpeed), iceSpeed >= 0.4);
		if (!legit.isEmpty()) {
			LOG.error("[MejtaAcTest] legitimate-play flags at {}: {}", p.name(), legit);
		}

		// ---- crafted cheats: must be detected
		String reach = reachCheats(ctx, server, arena, p);
		String multi = multiTargetCheat(ctx, server, arena, p);
		String timer = timerCheats(ctx, p);
		String ground = groundSpoofCheat(ctx, server, arena, p);
		String hitbox = hitboxCheat(ctx, server, arena, p);
		String blockReach = blockReachCheats(ctx, server, arena, p);
		String fly = flyCheats(ctx, server, arena, p);
		String speed = speedCheat(ctx, server, arena, p);
		String fastBreak = fastBreakCheats(ctx, server, arena, p);
		table.add(String.format(Locale.ROOT, "| %s | %d | %d (%d legit hits, up to %.2f blocks uncompensated) | %s | %s | %s | %s | %s | %s | %s | %s | %s |",
				p.name(), rtt, legit.size(), legitAttacks, uncompensated, timer, ground, fly, speed, reach, hitbox, multi, blockReach, fastBreak));
		resetViolations(server, arena);
	}

	private void idleWalkJump(ClientGameTestContext ctx, LatencySimulator sim, Profile p) {
		ctx.waitTicks(40);
		ctx.getInput().holdKeyFor(options -> options.keyUp, 15);
		ctx.getInput().holdKeyFor(options -> options.keyDown, 15);
		for (int i = 0; i < 3; i++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 3);
			ctx.waitTicks(15);
		}
		if (p.freeze()) {
			sim.freeze(1500);
			ctx.waitTicks(30);
			ctx.getInput().holdKeyFor(options -> options.keyUp, 10);
			ctx.getInput().holdKeyFor(options -> options.keyDown, 10);
			ctx.waitTicks(40);
		}
	}

	/** The target moves away along +X at 0.3 blocks/tick; the client hits it whenever it looks close enough. */
	private int movingTargetAttacks(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		Vec3 feet = arena.feet();
		int id = spawnStand(server, feet.add(1.8, 0, 0));
		moverOrigin = feet;
		moverDistance = 1.8;
		moverStand = (ArmorStand) server.computeOnServer(s -> s.overworld().getEntity(id));
		TestHooks.resetAttacks();
		ctx.waitTicks(20);
		for (int tick = 0; tick < 100; tick++) {
			final int t = tick;
			ctx.runOnClient(mc -> {
				Entity stand = mc.level.getEntity(id);
				if (stand == null) {
					return;
				}
				aim(mc, stand);
				double distance = Math.sqrt(stand.getBoundingBox().distanceToSqr(mc.player.getEyePosition()));
				if (distance <= 2.8 && t % 2 == 0) {
					KeyMapping.click(attackKey());
				}
			});
			ctx.waitTick();
		}
		moverStand = null;
		ctx.waitTicks(20);
		discard(server, id);
		return TestHooks.attacksReceived;
	}

	private static void moveStand() {
		ArmorStand stand = moverStand;
		Vec3 origin = moverOrigin;
		if (stand == null || origin == null) {
			return;
		}
		double distance = moverDistance + 0.3;
		if (distance > 7.0) {
			distance = 1.8;
		}
		moverDistance = distance;
		stand.setPos(origin.x + distance, origin.y, origin.z);
	}

	/** Four clicks queued within one client tick, three times, while aiming at a stand. */
	private boolean rapidClicks(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		int id = spawnStand(server, arena.feet().add(2.0, 0, 0));
		ctx.waitTicks(30);
		TestHooks.resetAttacks();
		for (int i = 0; i < 3; i++) {
			ctx.runOnClient(mc -> {
				Entity stand = mc.level.getEntity(id);
				if (stand != null) {
					aim(mc, stand);
				}
			});
			ctx.waitTick();
			ctx.runOnClient(mc -> {
				for (int c = 0; c < 4; c++) {
					KeyMapping.click(attackKey());
				}
			});
			ctx.waitTicks(15);
		}
		ctx.waitTicks(20);
		discard(server, id);
		List<List<Integer>> ticks = TestHooks.ticksWithSeveralAttacks;
		LOG.info("[MejtaAcTest] client ticks with several attacks: {}", ticks);
		return !ticks.isEmpty() && ticks.stream().allMatch(list -> new HashSet<>(list).size() == 1);
	}

	/** The 3x3 floor under the player is removed; the client only finds out one round trip later. */
	private void floorDisappears(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air",
				arena.x() - 1, arena.y(), arena.z() - 1, arena.x() + 1, arena.y(), arena.z() + 1));
		ctx.waitTicks(60);
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone",
				arena.x() - 1, arena.y(), arena.z() - 1, arena.x() + 1, arena.y(), arena.z() + 1));
		teleportHome(ctx, server, arena);
	}

	private String reachCheats(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		String result = "";
		for (double distance : new double[] {4.2, 3.3, 3.05}) {
			int id = spawnStand(server, arena.feet().add(distance + 0.25, 0, 0));
			ctx.waitTicks(p.rttMs() / 50 + 15);
			int start = flags.size();
			for (int i = 0; i < 3; i++) {
				ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(id)));
				ctx.waitTicks(3);
			}
			ctx.waitTicks(p.rttMs() / 50 + 10);
			List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(ReachCheck.ID)).toList();
			discard(server, id);
			boolean high = got.stream().anyMatch(f -> f.confidence() == Confidence.HIGH);
			boolean medium = got.stream().anyMatch(f -> f.confidence() == Confidence.MEDIUM);
			if (distance == 4.2) {
				check(p.name() + ": reach 4.2 flagged HIGH", high);
				result += high ? "4.2: HIGH" : "4.2: missed";
			} else if (distance == 3.3) {
				check(p.name() + ": reach 3.3 flagged MEDIUM only", medium && !high);
				result += medium && !high ? ", 3.3: MEDIUM" : ", 3.3: wrong";
			} else {
				check(p.name() + ": reach 3.05 within tolerance, not flagged", got.isEmpty());
				result += got.isEmpty() ? ", 3.05: none" : ", 3.05: flagged";
			}
		}
		resetViolations(server, arena);
		return result;
	}

	private String multiTargetCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		int a = spawnStand(server, arena.feet().add(2.0, 0, 0));
		int b = spawnStand(server, arena.feet().add(-2.0, 0, 0));
		ctx.waitTicks(p.rttMs() / 50 + 15);
		int start = flags.size();
		long t0 = System.nanoTime();
		ctx.runOnClient(mc -> {
			mc.getConnection().send(new ServerboundAttackPacket(a));
			mc.getConnection().send(new ServerboundAttackPacket(b));
		});
		boolean detected = waitUntil(ctx, p.rttMs() / 50 + 40, () -> firstFlag(start, MultiTargetCheck.ID) != null);
		FlagEvent first = firstFlag(start, MultiTargetCheck.ID);
		check(p.name() + ": two targets in one tick flagged CERTAIN", detected && first.confidence() == Confidence.CERTAIN);
		discard(server, a);
		discard(server, b);
		return detected ? (first.nanos() - t0) / 1_000_000 + " ms" : "missed";
	}

	private String timerCheats(ClientGameTestContext ctx, Profile p) {
		String fast = timerRun(ctx, p, "2x", 1, 1, 120);
		String medium = timerRun(ctx, p, "1.3x", 3, 10, 240);
		String slow = timerRun(ctx, p, "1.05x", 1, 20, 160);
		check(p.name() + ": timer 2x detected", !fast.equals("missed"));
		check(p.name() + ": timer 1.3x detected", !medium.equals("missed"));
		check(p.name() + ": timer 1.05x stays below tolerance in 8 s", slow.equals("missed"));
		return "2x: " + fast + ", 1.3x: " + medium + ", 1.05x: " + (slow.equals("missed") ? "below tolerance" : slow);
	}

	/** Sends {@code extra} additional tick-end packets every {@code per} client ticks. */
	private String timerRun(ClientGameTestContext ctx, Profile p, String label, int extra, int per, int maxTicks) {
		ConfigHandle config = config();
		config.set(AcSettings.TIMER_ENABLED, false); // resets every player's timer state
		config.set(AcSettings.TIMER_ENABLED, true);
		ctx.waitTicks(10);
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int tick = 0; tick < maxTicks && firstFlag(start, TimerCheck.ID) == null; tick++) {
			if (tick % per < extra) {
				ctx.runOnClient(mc -> mc.getConnection().send(ServerboundClientTickEndPacket.INSTANCE));
			}
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		FlagEvent first = firstFlag(start, TimerCheck.ID);
		LOG.info("[MejtaAcTest] timer {} at {}: {}", label, p.name(), first == null ? "not flagged" : "flagged");
		return first == null ? "missed" : (first.nanos() - t0) / 1_000_000 + " ms";
	}

	private String groundSpoofCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		Vec3 home = arena.feet();
		TestHooks.spoofGround = true;
		int start = flags.size();
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", arena.name(), home.x, home.y + 40, home.z));
		long t0 = System.nanoTime();
		boolean detected = waitUntil(ctx, 120, () -> firstFlag(start, GroundSpoofCheck.ID) != null);
		waitUntil(ctx, 120, () -> ctx.computeOnClient(mc -> mc.player.getY() < home.y + 0.5));
		ctx.waitTicks(10);
		TestHooks.spoofGround = false;
		FlagEvent first = firstFlag(start, GroundSpoofCheck.ID);
		check(p.name() + ": ground spoof while falling detected", detected);
		teleportHome(ctx, server, arena);
		return detected ? (first.nanos() - t0) / 1_000_000 + " ms" : "missed";
	}

	// ---------------------------------------------------------------- Stage 4b scenarios

	/** Real input: start breaking a block 3.5 blocks away and place stone against it. */
	private int blockInteractions(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		BlockPos target = new BlockPos(arena.x() + 4, arena.y() + 2, arena.z());
		server.runCommand(String.format("setblock %d %d %d minecraft:stone", target.getX(), target.getY(), target.getZ()));
		server.runCommand("give " + arena.name() + " minecraft:stone 16");
		ctx.waitTicks(20);
		int start = TestHooks.blockActionsReceived;
		for (int i = 0; i < 4; i++) {
			ctx.runOnClient(mc -> aimAt(mc, Vec3.atCenterOf(target)));
			ctx.waitTick();
			ctx.runOnClient(mc -> KeyMapping.click(mouseKey(GLFW.GLFW_MOUSE_BUTTON_LEFT)));
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> KeyMapping.click(mouseKey(GLFW.GLFW_MOUSE_BUTTON_RIGHT)));
			ctx.waitTicks(10);
		}
		int actions = TestHooks.blockActionsReceived - start;
		server.runCommand("clear " + arena.name());
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air",
				arena.x() - 8, arena.y() + 1, arena.z() - 8, arena.x() + 8, arena.y() + 12, arena.z() + 8));
		return actions;
	}

	/**
	 * The server launches the player upwards and sideways, as knockback or a wind charge would.
	 *
	 * @return the highest the client rose above its starting height
	 */
	private double knockbackLaunch(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		double startY = ctx.computeOnClient(mc -> mc.player.getY());
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
			player.setDeltaMovement(0.25, 0.9, 0);
			player.hurtMarked = true;
		});
		double maxY = startY;
		for (int i = 0; i < 80; i++) {
			maxY = Math.max(maxY, ctx.computeOnClient(mc -> mc.player.getY()));
			ctx.waitTick();
		}
		heal(server, arena);
		teleportHome(ctx, server, arena);
		return maxY - startY;
	}

	/**
	 * Falling 11 blocks onto slime: several bounces, each one starts a new upward trajectory.
	 *
	 * @return how many times the client started moving up again after falling
	 */
	private int slimeBounce(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		int sx = arena.x() + 3;
		server.runCommand(String.format("setblock %d %d %d minecraft:slime_block", sx, arena.y(), arena.z()));
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", arena.name(), sx + 0.5, arena.y() + 12, arena.z() + 0.5));
		int bounces = 0;
		double previousY = Double.NaN;
		boolean falling = false;
		for (int i = 0; i < 140; i++) {
			double y = ctx.computeOnClient(mc -> mc.player.getY());
			if (!Double.isNaN(previousY)) {
				if (y < previousY - 0.05) {
					falling = true;
				} else if (falling && y > previousY + 0.05 && y < arena.y() + 6) {
					bounces++;
					falling = false;
				}
			}
			previousY = y;
			ctx.waitTick();
		}
		server.runCommand(String.format("setblock %d %d %d minecraft:stone", sx, arena.y(), arena.z()));
		heal(server, arena);
		teleportHome(ctx, server, arena);
		return bounces;
	}

	private String hitboxCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int id = spawnStand(server, arena.feet().add(2.0, 0, 0));
		ctx.runOnClient(mc -> {
			mc.player.setYRot(90); // facing -X, away from the stand
			mc.player.setXRot(0);
		});
		ctx.waitTicks(p.rttMs() / 50 + 15);
		int start = flags.size();
		long t0 = System.nanoTime();
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(id)));
		boolean detected = waitUntil(ctx, p.rttMs() / 50 + 40, () -> firstFlag(start, HitboxCheck.ID) != null);
		FlagEvent first = firstFlag(start, HitboxCheck.ID);
		check(p.name() + ": attack while looking away flagged HIGH", detected && first.confidence() == Confidence.HIGH);
		discard(server, id);
		resetViolations(server, arena);
		return detected ? first.confidence() + " after " + (first.nanos() - t0) / 1_000_000 + " ms" : "missed";
	}

	private String blockReachCheats(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int eyeY = arena.y() + 2;
		record Case(BlockPos pos, String distance, String expect) {
		}
		List<Case> cases = List.of(
				new Case(new BlockPos(arena.x() + 6, eyeY, arena.z()), "5.5", "HIGH"),
				new Case(new BlockPos(arena.x() + 5, eyeY, arena.z() + 2), "4.74", "MEDIUM"),
				new Case(new BlockPos(arena.x() + 4, eyeY, arena.z() + 1), "3.54", "none"));
		StringBuilder result = new StringBuilder();
		for (Case c : cases) {
			int start = flags.size();
			ctx.runOnClient(mc -> mc.getConnection().send(
					new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, c.pos(), Direction.UP)));
			ctx.runOnClient(mc -> mc.getConnection().send(
					new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, c.pos(), Direction.UP)));
			ctx.waitTicks(p.rttMs() / 50 + 10);
			List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(BlockReachCheck.ID)).toList();
			String actual = got.isEmpty() ? "none" : got.get(0).confidence().name();
			check(p.name() + ": block reach at " + c.distance() + " blocks -> " + c.expect(), actual.equals(c.expect()));
			result.append(result.isEmpty() ? "" : ", ").append(c.distance()).append(": ").append(actual);
		}
		resetViolations(server, arena);
		return result.toString();
	}

	private String flyCheats(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		String hover = flyRun(ctx, server, arena, p, "hover", 0.0, 2, 40);
		String ascend = flyRun(ctx, server, arena, p, "ascend", 0.2, 2, 30);
		String glide = flyRun(ctx, server, arena, p, "glide", -0.05, 15, 40);
		return "hover: " + hover + ", ascend: " + ascend + ", glide: " + glide;
	}

	/** The real client forces its vertical speed to {@code vy} every tick after rising {@code startHeight} blocks. */
	private String flyRun(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p, String label,
			double vy, int startHeight, int ticks) {
		teleportHome(ctx, server, arena);
		Vec3 home = arena.feet();
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", arena.name(), home.x, home.y + startHeight, home.z));
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(home.add(0, startHeight, 0)) < 0.5));
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < ticks; i++) {
			ctx.runOnClient(mc -> mc.player.setDeltaMovement(0, vy, 0));
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		FlagEvent first = firstFlag(start, FlyCheck.ID);
		FlagEvent high = flagsSince(start).stream()
				.filter(f -> f.check().equals(FlyCheck.ID) && f.confidence() == Confidence.HIGH).findFirst().orElse(null);
		check(p.name() + ": fly " + label + " detected", first != null);
		check(p.name() + ": fly " + label + " reaches HIGH when sustained", high != null);
		ctx.waitTicks(60);
		heal(server, arena);
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		if (first == null) {
			return "missed";
		}
		return (first.nanos() - t0) / 1_000_000 + " ms / " + (high == null ? "-" : (high.nanos() - t0) / 1_000_000 + " ms");
	}

	/**
	 * Real input: sprint-jumping across the platform along +X, optionally with Speed II or on packed
	 * ice (the fastest normal ground movement).
	 */
	private double sprintJumps(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, String variant) {
		Vec3 start = arena.feet().add(-6, 0, 0);
		if ("ice".equals(variant)) {
			server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:packed_ice",
					arena.x() - 8, arena.y(), arena.z() - 1, arena.x() + 8, arena.y(), arena.z() + 1));
		}
		if ("speed".equals(variant)) {
			server.runCommand("effect give " + arena.name() + " minecraft:speed 30 1 true");
		}
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f -90 0", arena.name(), start.x, start.y, start.z));
		waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(start) < 0.5));
		ctx.waitTicks(10);
		ctx.getInput().holdKey(options -> options.keyUp);
		ctx.getInput().holdKey(options -> options.keySprint);
		ctx.getInput().holdKey(options -> options.keyJump);
		double fastest = 0;
		Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
		for (int i = 0; i < ("ice".equals(variant) ? 18 : 25); i++) {
			ctx.waitTick();
			Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
			fastest = Math.max(fastest, Math.hypot(now.x - last.x, now.z - last.z));
			last = now;
		}
		ctx.getInput().releaseKey(options -> options.keyJump);
		ctx.getInput().releaseKey(options -> options.keySprint);
		ctx.getInput().releaseKey(options -> options.keyUp);
		ctx.waitTicks(30);
		if ("ice".equals(variant)) {
			server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone",
					arena.x() - 8, arena.y(), arena.z() - 1, arena.x() + 8, arena.y(), arena.z() + 1));
		}
		server.runCommand("effect clear " + arena.name());
		heal(server, arena);
		teleportHome(ctx, server, arena);
		return fastest;
	}

	/** Real input: hold attack on a dirt block until it breaks (15 ticks by hand). */
	private boolean mineDirt(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		BlockPos dirt = new BlockPos(arena.x() + 2, arena.y() + 1, arena.z());
		server.runCommand(String.format("setblock %d %d %d minecraft:dirt", dirt.getX(), dirt.getY(), dirt.getZ()));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> aimAt(mc, Vec3.atCenterOf(dirt)));
		ctx.waitTick();
		ctx.getInput().holdKeyFor(options -> options.keyAttack, 40);
		ctx.waitTicks(10);
		boolean broken = server.computeOnServer(s -> s.overworld().getBlockState(dirt).isAir());
		server.runCommand(String.format("setblock %d %d %d minecraft:air", dirt.getX(), dirt.getY(), dirt.getZ()));
		server.runCommand("kill @e[type=item]");
		return broken;
	}

	/** The real client forces 0.5 blocks/tick along the ground (sprinting is about 0.29). */
	private String speedCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		Vec3 start = arena.feet().add(-7, 0, 0);
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f -90 0", arena.name(), start.x, start.y, start.z));
		waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(start) < 0.5));
		ctx.waitTicks(10);
		int start0 = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < 24; i++) {
			ctx.runOnClient(mc -> mc.player.setDeltaMovement(0.5, mc.player.getDeltaMovement().y, 0));
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		FlagEvent first = firstFlag(start0, SpeedCheck.ID);
		FlagEvent high = flagsSince(start0).stream()
				.filter(f -> f.check().equals(SpeedCheck.ID) && f.confidence() == Confidence.HIGH).findFirst().orElse(null);
		check(p.name() + ": speed 0.5 blocks/tick detected", first != null);
		check(p.name() + ": speed reaches HIGH when sustained", high != null);
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		if (first == null) {
			return "missed";
		}
		return (first.nanos() - t0) / 1_000_000 + " ms / " + (high == null ? "-" : (high.nanos() - t0) / 1_000_000 + " ms");
	}

	/** Crafted start/finish packets around a dirt block that takes 15 ticks to break by hand. */
	private String fastBreakCheats(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		BlockPos dirt = new BlockPos(arena.x() + 2, arena.y() + 1, arena.z());
		record Case(int ticks, String expect) {
		}
		StringBuilder result = new StringBuilder();
		for (Case c : List.of(new Case(3, "HIGH"), new Case(11, "MEDIUM"), new Case(16, "none"))) {
			// A break finished too early is not discarded by vanilla: it completes the destroy itself once
			// enough server ticks have passed ("delayed destroy"). Let that happen before the next case.
			ctx.waitTicks(25);
			server.runCommand(String.format("setblock %d %d %d minecraft:dirt", dirt.getX(), dirt.getY(), dirt.getZ()));
			ctx.waitTicks(p.rttMs() / 50 + 5);
			check(p.name() + ": dirt present before the " + c.ticks() + "-tick case",
					server.computeOnServer(s -> s.overworld().getBlockState(dirt).is(net.minecraft.world.level.block.Blocks.DIRT)));
			int start = flags.size();
			ctx.runOnClient(mc -> mc.getConnection().send(
					new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, dirt, Direction.WEST)));
			ctx.waitTicks(c.ticks());
			ctx.runOnClient(mc -> mc.getConnection().send(
					new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, dirt, Direction.WEST)));
			ctx.waitTicks(p.rttMs() / 50 + 10);
			List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(FastBreakCheck.ID)).toList();
			String actual = got.isEmpty() ? "none" : got.get(0).confidence().name();
			check(p.name() + ": fast break after " + c.ticks() + " of 15 ticks -> " + c.expect(), actual.equals(c.expect()));
			result.append(result.isEmpty() ? "" : ", ").append(c.ticks()).append("/15: ").append(actual);
		}
		server.runCommand(String.format("setblock %d %d %d minecraft:air", dirt.getX(), dirt.getY(), dirt.getZ()));
		server.runCommand("kill @e[type=item]");
		resetViolations(server, arena);
		return result.toString();
	}


	private static void heal(TestDedicatedServerContext server, Arena arena) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
			if (player != null) {
				player.setHealth(player.getMaxHealth());
			}
		});
	}

	private static void aimAt(Minecraft mc, Vec3 point) {
		Vec3 to = point.subtract(mc.player.getEyePosition());
		double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, horizontal)));
	}

	private static InputConstants.Key mouseKey(int mouseButton) {
		return InputConstants.Type.MOUSE.getOrCreate(mouseButton);
	}

	// ---------------------------------------------------------------- helpers

	private static int spawnStand(TestDedicatedServerContext server, Vec3 pos) {
		return server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			ArmorStand stand = new ArmorStand(level, pos.x, pos.y, pos.z);
			stand.setInvulnerable(true);
			stand.setNoGravity(true);
			level.addFreshEntity(stand);
			return stand.getId();
		});
	}

	private static void discard(TestDedicatedServerContext server, int id) {
		server.runOnServer(s -> {
			Entity entity = s.overworld().getEntity(id);
			if (entity != null) {
				entity.discard();
			}
		});
	}

	private static void resetViolations(TestDedicatedServerContext server, Arena arena) {
		server.runOnServer(s -> AntiCheat.get().orElseThrow().violations().reset(arena.player()));
	}

	private static void aim(Minecraft mc, Entity target) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 to = target.getBoundingBox().getCenter().subtract(eye);
		double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, horizontal)));
	}

	private static InputConstants.Key attackKey() {
		return InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT);
	}

	/** Snapshot of the flags raised since {@code fromIndex}; the server thread keeps adding to {@link #flags}. */
	private List<FlagEvent> flagsSince(int fromIndex) {
		List<FlagEvent> all = List.copyOf(flags);
		return all.subList(Math.min(fromIndex, all.size()), all.size());
	}

	private FlagEvent firstFlag(int fromIndex, String checkId) {
		return flagsSince(fromIndex).stream().filter(f -> f.check().equals(checkId)).findFirst().orElse(null);
	}

	private static ConfigHandle config() {
		return MejtaCoreAPI.getAddon(MejtaAC.ID).flatMap(a -> a.config()).orElseThrow();
	}

	private static boolean waitUntil(ClientGameTestContext ctx, int maxTicks, BooleanSupplier condition) {
		for (int i = 0; i < maxTicks; i++) {
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
			LOG.info("[MejtaAcTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaAcTest] FAIL {}", name);
		}
	}

	private void writeTable() {
		List<String> lines = new ArrayList<>();
		lines.add("| Profile | Measured RTT (ms) | Legitimate-play flags | Timer (time to detect) | Ground spoof (time to detect) | Fly (time to first flag / to HIGH) | Speed (first flag / HIGH) | Reach (crafted hits) | Hitbox (crafted hit) | Multi-target (time to detect) | Block reach (crafted actions) | Fast break (crafted) |");
		lines.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
		lines.addAll(table);
		for (String line : lines) {
			LOG.info("[MejtaAcTest] TABLE {}", line);
		}
		try {
			Files.write(FabricLoader.getInstance().getGameDir().resolve("mejtaac-stage4-latency.md"), lines);
		} catch (IOException e) {
			LOG.error("[MejtaAcTest] could not write results table", e);
		}
	}
}
