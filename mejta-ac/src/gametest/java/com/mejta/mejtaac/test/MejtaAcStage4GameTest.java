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
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.AcSettings;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.MejtaAC;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.check.combat.MultiTargetCheck;
import com.mejta.mejtaac.check.combat.ReachCheck;
import com.mejta.mejtaac.check.movement.GroundSpoofCheck;
import com.mejta.mejtaac.check.movement.TimerCheck;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Stage 4a: timer, ground spoof, reach and multi-target, tested against latency.
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
		List<FlagEvent> legit = flagsSince(legitStart);
		for (String checkId : List.of(TimerCheck.ID, GroundSpoofCheck.ID, ReachCheck.ID, MultiTargetCheck.ID)) {
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
		if (!legit.isEmpty()) {
			LOG.error("[MejtaAcTest] legitimate-play flags at {}: {}", p.name(), legit);
		}

		// ---- crafted cheats: must be detected
		String reach = reachCheats(ctx, server, arena, p);
		String multi = multiTargetCheat(ctx, server, arena, p);
		String timer = timerCheats(ctx, p);
		String ground = groundSpoofCheat(ctx, server, arena, p);
		table.add(String.format(Locale.ROOT, "| %s | %d | %d (%d legit hits, up to %.2f blocks uncompensated) | %s | %s | %s | %s |",
				p.name(), rtt, legit.size(), legitAttacks, uncompensated, timer, ground, reach, multi));
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
		lines.add("| Profile | Measured RTT (ms) | Legitimate-play flags | Timer (time to detect) | Ground spoof (time to detect) | Reach (crafted hits) | Multi-target (time to detect) |");
		lines.add("|---|---|---|---|---|---|---|");
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
