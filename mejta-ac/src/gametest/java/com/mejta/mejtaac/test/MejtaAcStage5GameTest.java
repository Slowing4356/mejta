package com.mejta.mejtaac.test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.AcSettings;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.MejtaAC;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.movement.FlyCheck;
import com.mejta.mejtaac.check.movement.GroundSpoofCheck;
import com.mejta.mejtaac.check.movement.JumpCheck;
import com.mejta.mejtaac.check.movement.SpeedCheck;
import com.mejta.mejtaac.check.movement.TimerCheck;
import com.mejta.mejtaac.check.vehicle.BoatFlyCheck;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Stage 5: {@code movement.jump} and {@code vehicle.boat_fly}, tested against latency.
 *
 * <p>For every network profile a real (non-op) client first plays legitimately in the situations most
 * likely to cause false positives, and every movement check must stay silent:
 * <ul>
 * <li>jumps: in place, onto blocks, slabs and stairs, with Jump Boost II (also removed by the server
 * mid-jump while the client still has it), on honey, bouncing on a bed, near and on a boat;</li>
 * <li>teleports straight up and sideways (like ender pearls);</li>
 * <li>boats: placed under the player, driven off a 6-block ledge onto stone, slime and water, launched
 * by a bubble column, and left again on land and on water;</li>
 * <li>fake lag: with the jitter profile the network also freezes for 1.5 s in the middle of a boat
 * fall and of jumping.</li>
 * </ul>
 * Then the client cheats (high jump, boat hover and boat ascend) and both checks must catch it.
 * Results are written to {@code mejtaac-stage5-latency.md}.
 */
public final class MejtaAcStage5GameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaAcTest");

	private record Profile(String name, int rttMs, int jitterMs, boolean freeze) {
	}

	private static final List<Profile> PROFILES = List.of(
			new Profile("0 ms", 0, 0, false),
			new Profile("150 ms", 150, 0, false),
			new Profile("300 ms", 300, 0, false),
			new Profile("500 ms", 500, 0, false),
			new Profile("300 ms +-80 jitter + 1.5 s freezes", 300, 80, true));

	private static final List<String> MOVEMENT_CHECKS = List.of(JumpCheck.ID, BoatFlyCheck.ID, FlyCheck.ID, SpeedCheck.ID,
			GroundSpoofCheck.ID, TimerCheck.ID);

	private record FlagEvent(long nanos, String check, String reason, Confidence confidence, String measurements) {
	}

	private record Arena(UUID player, String name, int x, int y, int z) {
		Vec3 feet() {
			return new Vec3(x + 0.5, y + 1, z + 0.5);
		}
	}

	private final List<String> failures = new ArrayList<>();
	private final List<String> table = new ArrayList<>();
	private final List<FlagEvent> flags = new CopyOnWriteArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ConfigHandle config = config();
		config.set(AcSettings.DECAY_PER_MINUTE, 0.0);
		config.set(AcSettings.LOG_LEVEL, AcSettings.LogLevel.VERBOSE);
		config.save();
		AntiCheat.traceMovement = true;
		AntiCheat.get().orElseThrow().addFlagListener(d -> flags.add(new FlagEvent(System.nanoTime(), d.flag().checkId(),
				d.flag().reason(), d.flag().confidence(), d.flag().measurements().toString())));

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			server.runCommand("difficulty peaceful");
			server.runCommand("gamerule doDaylightCycle false");
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

		AntiCheat.traceMovement = false;
		config.resetAll();
		config.save();
		writeTable();
		LOG.info("[MejtaAcTest] STAGE5 SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaAC Stage 5 tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- arena

	/**
	 * A 17×17 stone platform at y, a lower stone floor 6 blocks below that reaches 20 blocks east of the
	 * platform (for boats driven off the edge), and a packed-ice runway leading to the east edge.
	 */
	private Arena buildArena(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
		String name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		int x = ctx.computeOnClient(mc -> mc.player.getBlockX());
		int z = ctx.computeOnClient(mc -> mc.player.getBlockZ());
		int y = 150;
		fill(server, x - 8, y - 7, z - 8, x + 28, y + 14, z + 8, "air");
		fill(server, x - 8, y, z - 8, x + 8, y, z + 8, "stone");
		fill(server, x + 9, y - 6, z - 8, x + 28, y - 6, z + 8, "stone");
		walls(server, x, y, z);
		Arena arena = new Arena(id, name, x, y, z);
		teleportHome(ctx, server, arena);
		return arena;
	}

	/** Glass walls around the lower floor, so a bouncing boat cannot leave the arena. */
	private static void walls(TestDedicatedServerContext server, int x, int y, int z) {
		fill(server, x + 29, y - 6, z - 9, x + 29, y + 2, z + 9, "glass");
		fill(server, x + 9, y - 6, z - 9, x + 28, y - 1, z - 9, "glass");
		fill(server, x + 9, y - 6, z + 9, x + 28, y - 1, z + 9, "glass");
	}

	private static void fill(TestDedicatedServerContext server, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:%s", x1, y1, z1, x2, y2, z2, block));
	}

	private static void setblock(TestDedicatedServerContext server, int x, int y, int z, String block) {
		server.runCommand(String.format("setblock %d %d %d minecraft:%s", x, y, z, block));
	}

	private void teleportHome(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleport(ctx, server, arena, arena.feet(), 0);
	}

	private void teleport(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Vec3 to, float yaw) {
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", arena.name(), to.x, to.y, to.z, yaw));
		waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.onGround() && mc.player.position().distanceTo(to) < 0.6));
		ctx.waitTicks(10);
	}

	// ---------------------------------------------------------------- one latency profile

	private void runProfile(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim, Profile p) {
		LOG.info("[MejtaAcTest] ===== stage 5 profile {} =====", p.name());
		sim.set(p.rttMs() / 2, p.jitterMs() / 2);
		ctx.waitTicks(80);
		int rtt = server.computeOnServer(s -> AntiCheat.get().orElseThrow().sync().rttMs(arena.player()));
		resetViolations(server, arena);

		// ---- legitimate play: no movement check may flag
		int legitStart = flags.size();
		List<String> evidence = new ArrayList<>();
		evidence.add(jumpsInPlace(ctx, server, arena, sim, p));
		evidence.add(jumpsOntoSteps(ctx, server, arena));
		evidence.add(jumpBoostRemovedMidJump(ctx, server, arena, sim, p));
		evidence.add(honeyJumps(ctx, server, arena));
		evidence.add(bedBounce(ctx, server, arena));
		evidence.add(teleports(ctx, server, arena));
		evidence.add(boatPlacedUnderPlayer(ctx, server, arena));
		evidence.add(boatOffLedge(ctx, server, arena, sim, p, "stone"));
		evidence.add(boatOffLedge(ctx, server, arena, sim, p, "slime_block"));
		evidence.add(boatOffLedge(ctx, server, arena, sim, p, "water"));
		evidence.add(bubbleColumnBoat(ctx, server, arena));
		List<FlagEvent> legit = flagsSince(legitStart);
		for (String checkId : MOVEMENT_CHECKS) {
			List<FlagEvent> of = legit.stream().filter(f -> f.check().equals(checkId)).toList();
			check(p.name() + ": legitimate play, no " + checkId + " flags (" + of.size() + ")", of.isEmpty());
		}
		if (!legit.isEmpty()) {
			LOG.error("[MejtaAcTest] stage 5 legitimate-play flags at {}: {}", p.name(), legit);
		}
		LOG.info("[MejtaAcTest] {}: legitimate scenarios: {}", p.name(), evidence);

		// ---- cheats: must be detected
		String highJump = highJumpCheat(ctx, server, arena, p);
		String hover = boatFlyCheat(ctx, server, arena, p, "hover", 0.0);
		String ascend = boatFlyCheat(ctx, server, arena, p, "ascend", 0.2);
		table.add(String.format(Locale.ROOT, "| %s | %d | %d | %s | %s | %s |", p.name(), rtt, legit.size(), highJump, hover, ascend));
		resetViolations(server, arena);
	}

	// ---------------------------------------------------------------- legitimate jumps

	/** Real input: ten jumps in place, with a network freeze in the middle for the jitter profile. */
	private String jumpsInPlace(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim, Profile p) {
		teleportHome(ctx, server, arena);
		double startY = ctx.computeOnClient(mc -> mc.player.getY());
		double peak = startY;
		for (int i = 0; i < 10; i++) {
			if (p.freeze() && i == 5) {
				sim.freeze(1500);
			}
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			for (int t = 0; t < 14; t++) {
				peak = Math.max(peak, ctx.computeOnClient(mc -> mc.player.getY()));
				ctx.waitTick();
			}
		}
		ctx.waitTicks(p.rttMs() / 50 + 20);
		double height = peak - startY;
		check(p.name() + String.format(Locale.ROOT, ": real jumps reached vanilla height (%.3f blocks)", height),
				height > 1.2 && height < 1.3);
		return String.format(Locale.ROOT, "jumps %.3f", height);
	}

	/** Real input: walking east with jump held over a block, a slab and stairs; then walking up a slab without jumping. */
	private String jumpsOntoSteps(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		int y = arena.y() + 1;
		fill(server, arena.x() - 2, y, arena.z() - 1, arena.x() - 2, y, arena.z() + 1, "stone");
		fill(server, arena.x() + 1, y, arena.z() - 1, arena.x() + 1, y, arena.z() + 1, "stone_slab");
		fill(server, arena.x() + 4, y, arena.z() - 1, arena.x() + 4, y, arena.z() + 1, "stone_stairs[facing=east]");
		Vec3 start = arena.feet().add(-6, 0, 0);
		teleport(ctx, server, arena, start, -90);
		double startY = ctx.computeOnClient(mc -> mc.player.getY());
		ctx.getInput().holdKey(options -> options.keyUp);
		ctx.getInput().holdKey(options -> options.keyJump);
		double peak = startY;
		for (int i = 0; i < 90 && ctx.computeOnClient(mc -> mc.player.getX()) < arena.x() + 6; i++) {
			peak = Math.max(peak, ctx.computeOnClient(mc -> mc.player.getY()));
			ctx.waitTick();
		}
		ctx.getInput().releaseKey(options -> options.keyJump);
		ctx.getInput().releaseKey(options -> options.keyUp);
		double reachedX = ctx.computeOnClient(mc -> mc.player.getX());
		// Without jumping: step up the slab (0.5) and down again.
		teleport(ctx, server, arena, arena.feet().add(-1, 0, 0), -90);
		ctx.getInput().holdKeyFor(options -> options.keyUp, 20);
		ctx.waitTicks(10);
		fill(server, arena.x() - 2, y, arena.z() - 1, arena.x() + 4, y + 1, arena.z() + 1, "air");
		check("jumping onto steps really climbed them (peak " + String.format(Locale.ROOT, "%.2f", peak - startY) + ")",
				peak - startY > 1.4 && reachedX > arena.x() + 4);
		return String.format(Locale.ROOT, "steps %.2f", peak - startY);
	}

	/**
	 * Real input: holding jump with Jump Boost II; the server removes the effect while the client still
	 * has it for one round trip (and, with the jitter profile, while the network is frozen).
	 */
	private String jumpBoostRemovedMidJump(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena,
			LatencySimulator sim, Profile p) {
		teleportHome(ctx, server, arena);
		server.runCommand("effect give " + arena.name() + " minecraft:jump_boost 60 1 true");
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> !mc.player.getActiveEffects().isEmpty()));
		double startY = ctx.computeOnClient(mc -> mc.player.getY());
		double peak = startY;
		ctx.getInput().holdKey(options -> options.keyJump);
		for (int i = 0; i < 60; i++) {
			if (i == 30) {
				if (p.freeze()) {
					sim.freeze(1500);
				}
				server.runCommand("effect clear " + arena.name());
			}
			peak = Math.max(peak, ctx.computeOnClient(mc -> mc.player.getY()));
			ctx.waitTick();
		}
		ctx.getInput().releaseKey(options -> options.keyJump);
		ctx.waitTicks(p.rttMs() / 50 + 30);
		check(p.name() + String.format(Locale.ROOT, ": Jump Boost II really jumped higher (%.2f blocks)", peak - startY), peak - startY > 1.8);
		return String.format(Locale.ROOT, "jump boost %.2f", peak - startY);
	}

	private String honeyJumps(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		setblock(server, arena.x(), arena.y(), arena.z(), "honey_block");
		teleport(ctx, server, arena, arena.feet().add(0, -0.0625, 0), 0);
		for (int i = 0; i < 4; i++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			ctx.waitTicks(12);
		}
		setblock(server, arena.x(), arena.y(), arena.z(), "stone");
		teleportHome(ctx, server, arena);
		return "honey";
	}

	/** Falling 8 blocks onto a bed: the player bounces. */
	private String bedBounce(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		int bx = arena.x() - 4;
		int by = arena.y() + 1;
		setblock(server, bx, by, arena.z(), "red_bed[part=foot,facing=east]");
		setblock(server, bx + 1, by, arena.z(), "red_bed[part=head,facing=east]");
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", arena.name(), bx + 0.5, by + 9, arena.z() + 0.5));
		int bounces = 0;
		double previous = Double.NaN;
		boolean falling = false;
		for (int i = 0; i < 80; i++) {
			double yNow = ctx.computeOnClient(mc -> mc.player.getY());
			if (!Double.isNaN(previous)) {
				if (yNow < previous - 0.05) {
					falling = true;
				} else if (falling && yNow > previous + 0.05 && yNow < by + 4) {
					bounces++;
					falling = false;
				}
			}
			previous = yNow;
			ctx.waitTick();
		}
		fill(server, bx, by, arena.z(), bx + 1, by, arena.z(), "air");
		heal(server, arena);
		teleportHome(ctx, server, arena);
		check("the player really bounced on the bed (" + bounces + ")", bounces >= 1);
		return "bed bounces " + bounces;
	}

	/** Teleports 3 blocks up and 12 blocks sideways, like ender pearls or /tp. */
	private String teleports(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		for (int i = 0; i < 4; i++) {
			server.runCommand("execute as " + arena.name() + " at @s run tp @s ~ ~3 ~");
			ctx.waitTicks(30);
			server.runCommand("execute as " + arena.name() + " at @s run tp @s ~" + (i % 2 == 0 ? 6 : -6) + " ~ ~");
			ctx.waitTicks(30);
		}
		teleportHome(ctx, server, arena);
		return "teleports";
	}

	// ---------------------------------------------------------------- legitimate boats

	private int summonBoat(TestDedicatedServerContext server, Vec3 pos, float yaw) {
		return server.computeOnServer(s -> {
			AbstractBoat boat = (AbstractBoat) EntityType.OAK_BOAT.create(s.overworld(), EntitySpawnReason.COMMAND);
			boat.snapTo(pos.x, pos.y, pos.z, yaw, 0);
			s.overworld().addFreshEntity(boat);
			return boat.getId();
		});
	}

	private void mount(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, int boatId) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
			Entity boat = s.overworld().getEntity(boatId);
			player.startRiding(boat, true, true);
		});
		boolean mounted = waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.getVehicle() != null));
		if (!mounted) {
			throw new AssertionError("could not mount the test boat (player dead or boat missing)");
		}
		ctx.waitTicks(5);
	}

	private void dismountAndRemove(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, int boatId) {
		ctx.getInput().holdKeyFor(options -> options.keyShift, 3);
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.getVehicle() == null));
		// Right after leaving the boat: jump a few times and walk (dismount placement must not flag).
		for (int i = 0; i < 3; i++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			ctx.waitTicks(12);
		}
		ctx.getInput().holdKeyFor(options -> options.keyLeft, 10);
		ctx.waitTicks(10);
		server.runOnServer(s -> {
			Entity boat = s.overworld().getEntity(boatId);
			if (boat != null) {
				boat.discard();
			}
		});
		heal(server, arena);
	}

	/** A boat appears right where the player stands ("dropping a boat" on oneself), then the player jumps around it. */
	private String boatPlacedUnderPlayer(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		int id = summonBoat(server, arena.feet(), 0);
		for (int i = 0; i < 5; i++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			ctx.waitTicks(12);
		}
		ctx.getInput().holdKeyFor(options -> options.keyUp, 10);
		ctx.waitTicks(20);
		server.runOnServer(s -> {
			Entity boat = s.overworld().getEntity(id);
			if (boat != null) {
				boat.discard();
			}
		});
		teleportHome(ctx, server, arena);
		return "boat under player";
	}

	/**
	 * Drives a boat along a packed-ice runway off the platform's east edge; it falls 6 blocks onto
	 * {@code floor} (stone, slime or water). With the jitter profile the network freezes mid-fall.
	 */
	private String boatOffLedge(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim,
			Profile p, String floor) {
		int lower = arena.y() - 6;
		fill(server, arena.x() + 1, arena.y(), arena.z() - 1, arena.x() + 8, arena.y(), arena.z() + 1, "packed_ice");
		if (floor.equals("water")) {
			fill(server, arena.x() + 9, lower - 1, arena.z() - 8, arena.x() + 28, lower - 1, arena.z() + 8, "stone");
			fill(server, arena.x() + 9, lower, arena.z() - 8, arena.x() + 28, lower, arena.z() + 8, "water");
		} else {
			fill(server, arena.x() + 9, lower, arena.z() - 8, arena.x() + 28, lower, arena.z() + 8, floor);
		}
		teleportHome(ctx, server, arena);
		int id = summonBoat(server, arena.feet().add(1, 0, 0), -90);
		mount(ctx, server, arena, id);
		ctx.runOnClient(mc -> mc.player.setYRot(-90));
		double startY = ctx.computeOnClient(mc -> mc.player.getVehicle().getY());
		double lowest = startY;
		boolean froze = false;
		ctx.getInput().holdKey(options -> options.keyUp);
		for (int i = 0; i < 120; i++) {
			double y = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? Double.NaN : mc.player.getVehicle().getY());
			if (!Double.isNaN(y)) {
				lowest = Math.min(lowest, y);
				if (p.freeze() && !froze && y < startY - 1.5) {
					sim.freeze(1500);
					froze = true;
				}
			}
			if (i == 90) {
				ctx.getInput().releaseKey(options -> options.keyUp);
			}
			ctx.waitTick();
		}
		ctx.getInput().releaseKey(options -> options.keyUp);
		ctx.waitTicks(p.rttMs() / 50 + 20);
		dismountAndRemove(ctx, server, arena, id);
		fill(server, arena.x() + 9, lower - 1, arena.z() - 8, arena.x() + 28, lower, arena.z() + 8, "air");
		fill(server, arena.x() + 9, lower, arena.z() - 8, arena.x() + 28, lower, arena.z() + 8, "stone");
		fill(server, arena.x() + 1, arena.y(), arena.z() - 1, arena.x() + 8, arena.y(), arena.z() + 1, "stone");
		teleportHome(ctx, server, arena);
		double drop = startY - lowest;
		check(p.name() + String.format(Locale.ROOT, ": boat really fell off the ledge onto %s (%.2f blocks)", floor, drop), drop > 5.0);
		return String.format(Locale.ROOT, "boat onto %s %.1f", floor, drop);
	}

	/** A boat on an upward bubble column (soul sand) is thrown up and falls back; then the player leaves it on water. */
	private String bubbleColumnBoat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		int cx = arena.x() - 5;
		int cz = arena.z() + 5;
		int top = arena.y();
		fill(server, cx - 2, top - 4, cz - 2, cx + 2, top, cz + 2, "stone");
		fill(server, cx - 1, top - 3, cz - 1, cx + 1, top - 3, cz + 1, "soul_sand");
		fill(server, cx - 1, top - 2, cz - 1, cx + 1, top, cz + 1, "water");
		waitUntil(ctx, 100, () -> server.computeOnServer(s -> s.overworld()
				.getBlockState(new net.minecraft.core.BlockPos(cx, top, cz)).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)));
		boolean column = server.computeOnServer(s -> s.overworld()
				.getBlockState(new net.minecraft.core.BlockPos(cx, top, cz)).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN));
		teleport(ctx, server, arena, arena.feet().add(-2, 0, 5), 90);
		int id = summonBoat(server, new Vec3(cx + 0.5, top + 0.9, cz + 0.5), 0);
		mount(ctx, server, arena, id);
		double startY = ctx.computeOnClient(mc -> mc.player.getVehicle().getY());
		double highest = startY;
		for (int i = 0; i < 200 && highest - startY < 1.0; i++) {
			double y = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? Double.NaN : mc.player.getVehicle().getY());
			if (!Double.isNaN(y)) {
				highest = Math.max(highest, y);
			}
			ctx.waitTick();
		}
		ctx.waitTicks(60); // fall back down and settle
		dismountAndRemove(ctx, server, arena, id);
		fill(server, cx - 2, top - 4, cz - 2, cx + 2, top - 1, cz + 2, "air");
		fill(server, cx - 2, top, cz - 2, cx + 2, top, cz + 2, "stone");
		teleportHome(ctx, server, arena);
		check("bubble column formed under the boat", column);
		check("bubble column really lifted the boat (" + String.format(Locale.ROOT, "%.2f", highest - startY) + ")",
				highest - startY > 0.3);
		return String.format(Locale.ROOT, "bubble column %.2f", highest - startY);
	}

	// ---------------------------------------------------------------- cheats

	/** The real client takes off at 0.75 blocks/tick instead of 0.42, five times. */
	private String highJumpCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < 5; i++) {
			waitUntil(ctx, 60, () -> ctx.computeOnClient(mc -> mc.player.onGround()));
			ctx.runOnClient(mc -> mc.player.setDeltaMovement(0, 0.75, 0));
			ctx.waitTicks(30);
		}
		ctx.waitTicks(p.rttMs() / 50 + 10);
		List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(JumpCheck.ID)).toList();
		FlagEvent high = got.stream().filter(f -> f.confidence() == Confidence.HIGH).findFirst().orElse(null);
		check(p.name() + ": high jump flagged on every jump (" + got.size() + "/5)", got.size() == 5);
		check(p.name() + ": first high jump is MEDIUM", !got.isEmpty() && got.get(0).confidence() == Confidence.MEDIUM);
		check(p.name() + ": repeated high jumps reach HIGH", high != null);
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		if (got.isEmpty()) {
			return "missed";
		}
		return got.size() + "/5 flagged, first " + (got.get(0).nanos() - t0) / 1_000_000 + " ms, HIGH "
				+ (high == null ? "-" : (high.nanos() - t0) / 1_000_000 + " ms");
	}

	/** The real client keeps its boat at a constant vertical speed {@code vy} (0 = hover) in the air. */
	private String boatFlyCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p, String label,
			double vy) {
		teleportHome(ctx, server, arena);
		int id = summonBoat(server, arena.feet().add(1, 0, 0), 0);
		mount(ctx, server, arena, id);
		resetViolations(server, arena);
		// Lift off: three ticks up, then the cheat speed.
		for (int i = 0; i < 3; i++) {
			ctx.runOnClient(mc -> setBoatVy(mc, 0.3));
			ctx.waitTick();
		}
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < 40; i++) {
			ctx.runOnClient(mc -> setBoatVy(mc, vy));
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		FlagEvent first = firstFlag(start, BoatFlyCheck.ID);
		FlagEvent high = flagsSince(start).stream()
				.filter(f -> f.check().equals(BoatFlyCheck.ID) && f.confidence() == Confidence.HIGH).findFirst().orElse(null);
		check(p.name() + ": boat " + label + " detected", first != null);
		check(p.name() + ": boat " + label + " reaches HIGH when sustained", high != null);
		dismountAndRemove(ctx, server, arena, id);
		ctx.waitTicks(40);
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		if (first == null) {
			return "missed";
		}
		return (first.nanos() - t0) / 1_000_000 + " ms / " + (high == null ? "-" : (high.nanos() - t0) / 1_000_000 + " ms");
	}

	/** Sets the boat's vertical speed so that, after vanilla subtracts gravity this tick, it moves by {@code vy}. */
	private static void setBoatVy(Minecraft mc, double vy) {
		Entity boat = mc.player.getVehicle();
		if (boat != null) {
			Vec3 v = boat.getDeltaMovement();
			boat.setDeltaMovement(v.x, vy + boat.getGravity(), v.z);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void heal(TestDedicatedServerContext server, Arena arena) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
			if (player != null) {
				player.setHealth(player.getMaxHealth());
			}
		});
	}

	private static void resetViolations(TestDedicatedServerContext server, Arena arena) {
		server.runOnServer(s -> AntiCheat.get().orElseThrow().violations().reset(arena.player()));
	}

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
		lines.add("| Profile | Measured RTT (ms) | Legitimate-play flags | High jump 0.75 (flagged, first, HIGH) | Boat hover (first / HIGH) | Boat ascend 0.2 (first / HIGH) |");
		lines.add("|---|---|---|---|---|---|");
		lines.addAll(table);
		for (String line : lines) {
			LOG.info("[MejtaAcTest] TABLE5 {}", line);
		}
		try {
			Files.write(FabricLoader.getInstance().getGameDir().resolve("mejtaac-stage5-latency.md"), lines);
		} catch (IOException e) {
			LOG.error("[MejtaAcTest] could not write results table", e);
		}
	}
}
