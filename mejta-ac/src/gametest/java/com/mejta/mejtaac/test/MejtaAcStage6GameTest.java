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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.AcSettings;
import com.mejta.mejtaac.AntiCheat;
import com.mejta.mejtaac.MejtaAC;
import com.mejta.mejtaac.check.Confidence;
import com.mejta.mejtaac.check.combat.HitboxCheck;
import com.mejta.mejtaac.check.combat.MultiTargetCheck;
import com.mejta.mejtaac.check.combat.ReachCheck;
import com.mejta.mejtaac.check.interaction.BlockReachCheck;
import com.mejta.mejtaac.check.interaction.FastBreakCheck;
import com.mejta.mejtaac.check.movement.ElytraCheck;
import com.mejta.mejtaac.check.movement.FlyCheck;
import com.mejta.mejtaac.check.movement.GroundSpoofCheck;
import com.mejta.mejtaac.check.movement.JumpCheck;
import com.mejta.mejtaac.check.movement.SpeedCheck;
import com.mejta.mejtaac.check.movement.TimerCheck;
import com.mejta.mejtaac.check.vehicle.BoatFlyCheck;
import com.mejta.mejtaac.check.vehicle.HorseCheck;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Stage 6 (MejtaAC 1.1.0): {@code movement.elytra}, {@code vehicle.horse} and mitigation, tested against
 * latency with the same five network profiles as stages 4 and 5. The whole suite runs with
 * {@code enforcement.mitigation} ON.
 *
 * <p>For every profile a real (non-op) client first plays legitimately, and no movement check may flag
 * (so nothing may be mitigated either):
 * <ul>
 * <li>elytra: a long glide with real input, steered around the arena, with level gliding, a dive, a pull-up,
 * looking straight down and up, a real firework rocket, a server push, and landing; in the jitter profile the
 * network also freezes mid-flight;</li>
 * <li>horses: galloping in circles at the highest horse speed, full jumps with real input, a 1-block step,
 * riding off a 4-block ledge, packed ice, Speed II removed by the server mid-gallop, a freeze mid-gallop;
 * and a camel walking and dashing.</li>
 * </ul>
 * Then the client cheats and the new checks must catch it: elytra hover, climb and boost without rockets;
 * horse hover, climb, high jump and speed.
 *
 * <p>Finally mitigation, which must undo each cheat: crafted reach and multi-target attacks are cancelled
 * (the target keeps its health, while the same attack without mitigation hurts it), a hitbox flag cancels the
 * next attacks for a moment, a block-reach break and a fast break are cancelled (the block stays), and a
 * client forced to fly up or run too fast is set back by the server. Results are written to
 * {@code mejtaac-stage6-latency.md}.
 */
public final class MejtaAcStage6GameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaAcTest");

	private record Profile(String name, int rttMs, int jitterMs, boolean freeze) {
	}

	private static final List<Profile> PROFILES = List.of(
			new Profile("0 ms", 0, 0, false),
			new Profile("150 ms", 150, 0, false),
			new Profile("300 ms", 300, 0, false),
			new Profile("500 ms", 500, 0, false),
			new Profile("300 ms +-80 jitter + 1.5 s freezes", 300, 80, true));

	private static final List<String> MOVEMENT_CHECKS = List.of(ElytraCheck.ID, HorseCheck.ID, FlyCheck.ID, SpeedCheck.ID,
			JumpCheck.ID, GroundSpoofCheck.ID, TimerCheck.ID, BoatFlyCheck.ID);

	private record FlagEvent(long nanos, String check, String reason, Confidence confidence, String mitigation, String measurements) {
	}

	/** The arena: a 65×65 stone floor at y, with air up to y + 85. */
	private record Arena(UUID player, String name, int x, int y, int z) {
		Vec3 feet() {
			return new Vec3(x + 0.5, y + 1, z + 0.5);
		}
	}

	private static final int HALF = 32;
	private static final int FLOOR_Y = 160;

	private final List<String> failures = new ArrayList<>();
	private final List<String> table = new ArrayList<>();
	private final List<FlagEvent> flags = new CopyOnWriteArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ConfigHandle config = config();
		config.set(AcSettings.DECAY_PER_MINUTE, 0.0);
		config.set(AcSettings.LOG_LEVEL, AcSettings.LogLevel.VERBOSE);
		config.set(AcSettings.ENFORCEMENT_MITIGATION, AcSettings.MitigationMode.ON);
		config.save();
		AntiCheat.get().orElseThrow().addFlagListener(d -> flags.add(new FlagEvent(System.nanoTime(), d.flag().checkId(),
				d.flag().reason(), d.flag().confidence(), d.mitigation(), d.flag().measurements().toString())));

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

		config.resetAll();
		config.save();
		writeTable();
		LOG.info("[MejtaAcTest] STAGE6 SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaAC Stage 6 tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- arena

	private Arena buildArena(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
		String name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		int x = ctx.computeOnClient(mc -> mc.player.getBlockX());
		int z = ctx.computeOnClient(mc -> mc.player.getBlockZ());
		int y = FLOOR_Y;
		for (int level = y + 1; level <= y + 85; level += 7) {
			fill(server, x - HALF, level, z - HALF, x + HALF, Math.min(level + 6, y + 85), z + HALF, "air");
		}
		fill(server, x - HALF, y, z - HALF, x + HALF, y, z + HALF, "stone");
		Arena arena = new Arena(id, name, x, y, z);
		teleportHome(ctx, server, arena);
		return arena;
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
		LOG.info("[MejtaAcTest] ===== stage 6 profile {} =====", p.name());
		sim.set(p.rttMs() / 2, p.jitterMs() / 2);
		ctx.waitTicks(80);
		int rtt = server.computeOnServer(s -> AntiCheat.get().orElseThrow().sync().rttMs(arena.player()));
		resetViolations(server, arena);

		// ---- legitimate play: no movement check may flag, so nothing is mitigated
		int legitStart = flags.size();
		List<String> evidence = new ArrayList<>();
		evidence.add(legitimateGlide(ctx, server, arena, sim, p));
		evidence.add(legitimateHorse(ctx, server, arena, sim, p));
		evidence.add(legitimateCamel(ctx, server, arena));
		List<FlagEvent> legit = flagsSince(legitStart);
		for (String checkId : MOVEMENT_CHECKS) {
			List<FlagEvent> of = legit.stream().filter(f -> f.check().equals(checkId)).toList();
			check(p.name() + ": legitimate play, no " + checkId + " flags (" + of.size() + ")", of.isEmpty());
		}
		check(p.name() + ": legitimate play, nothing mitigated", legit.stream().noneMatch(f -> !f.mitigation().isEmpty()));
		if (!legit.isEmpty()) {
			LOG.error("[MejtaAcTest] stage 6 legitimate-play flags at {}: {}", p.name(), legit);
		}
		LOG.info("[MejtaAcTest] {}: legitimate scenarios: {}", p.name(), evidence);

		// ---- cheats: must be detected
		String elytraHover = elytraCheat(ctx, server, arena, p, "hover");
		String elytraClimb = elytraCheat(ctx, server, arena, p, "climb");
		String elytraBoost = elytraCheat(ctx, server, arena, p, "boost");
		String horseHover = horseFlyCheat(ctx, server, arena, p, "hover", 0.0);
		String horseClimb = horseFlyCheat(ctx, server, arena, p, "climb", 0.2);
		String horseJump = horseHighJumpCheat(ctx, server, arena, p);
		String horseSpeed = horseSpeedCheat(ctx, server, arena, p);

		// ---- mitigation: the cheat's action is undone
		String combat = combatMitigation(ctx, server, arena, p);
		String blocks = blockMitigation(ctx, server, arena, p);
		String setbacks = setbackMitigation(ctx, server, arena, p);

		table.add(String.format(Locale.ROOT, "| %s | %d | %d | %s / %s / %s | %s / %s / %s / %s | %s | %s | %s |", p.name(), rtt, legit.size(),
				elytraHover, elytraClimb, elytraBoost, horseHover, horseClimb, horseJump, horseSpeed, combat, blocks, setbacks));
		resetViolations(server, arena);
	}

	// ---------------------------------------------------------------- legitimate elytra flight

	private void equipElytra(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		server.runCommand("clear " + arena.name());
		server.runCommand("item replace entity " + arena.name() + " armor.chest with minecraft:elytra");
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)));
	}

	private void unequipElytra(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		server.runCommand("clear " + arena.name());
		server.runCommand("item replace entity " + arena.name() + " armor.chest with minecraft:air");
		heal(server, arena);
		teleportHome(ctx, server, arena);
	}

	/** Teleports the player into the air at {@code to} and deploys the elytra with a real jump press. */
	private boolean takeOff(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Vec3 to, float yaw) {
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", arena.name(), to.x, to.y, to.z, yaw));
		waitUntil(ctx, 200, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(to) < 1.5));
		ctx.waitTicks(4);
		ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
		return waitUntil(ctx, 20, () -> ctx.computeOnClient(mc -> mc.player.isFallFlying()));
	}

	/**
	 * Heading (yaw) that circles the arena centre at about {@code radius} blocks: the tangent, bent inwards when
	 * too far out and outwards when too close.
	 */
	private static double orbitHeading(Entity entity, Arena arena, double radius) {
		double dx = arena.x() + 0.5 - entity.getX();
		double dz = arena.z() + 0.5 - entity.getZ();
		double distance = Math.hypot(dx, dz);
		double toCentre = Math.toDegrees(Math.atan2(-dx, dz));
		return toCentre + 90 - Math.max(-60, Math.min(60, (distance - radius) * 6));
	}

	/** Yaw for a mount or a cheating glider, whose movement follows the look direction at once. */
	private static float orbitYaw(Minecraft mc, Arena arena, double radius) {
		return (float) orbitHeading(mc.player, arena, radius);
	}

	/**
	 * Yaw for a real glider: vanilla only turns the glide velocity 10 % towards the look direction per tick,
	 * so the look leads the velocity by up to 90°.
	 */
	private static float glideYaw(Minecraft mc, Arena arena, double radius) {
		double desired = orbitHeading(mc.player, arena, radius);
		Vec3 v = mc.player.getDeltaMovement();
		if (Math.hypot(v.x, v.z) < 0.1) {
			return (float) desired;
		}
		double heading = Math.toDegrees(Math.atan2(-v.x, v.z));
		double error = ((desired - heading) % 360 + 540) % 360 - 180;
		return (float) (heading + Math.max(-90, Math.min(90, error * 3)));
	}

	/**
	 * Real input: a long glide from 75 blocks up, steered around the arena, with a level glide, a dive, a
	 * pull-up, looking straight down and up, a real firework rocket, a server push and a gentle landing.
	 */
	private String legitimateGlide(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim,
			Profile p) {
		equipElytra(ctx, server, arena);
		server.runCommand("item replace entity " + arena.name() + " weapon.mainhand with minecraft:firework_rocket 4");
		boolean deployed = takeOff(ctx, server, arena, new Vec3(arena.x() + 20.5, arena.y() + 75, arena.z() + 0.5), 0);
		int glidingTicks = 0;
		double fastest = 0;
		double boosted = 0;
		double climbed = 0;
		Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
		boolean landed = false;
		for (int i = 0; i < 700 && !landed; i++) {
			final int t = i;
			// Phases: level glide, dive, pull-up, straight down, straight up, firework, push, glide down.
			float pitch = t < 60 ? 5 : t < 85 ? 50 : t < 105 ? -35 : t < 113 ? 90 : t < 119 ? -90 : t < 180 ? 0 : t < 230 ? 8 : 15;
			ctx.runOnClient(mc -> {
				mc.player.setYRot(glideYaw(mc, arena, 20));
				mc.player.setXRot(pitch);
			});
			if (t == 140) {
				ctx.getInput().holdKeyFor(options -> options.keyUse, 1); // a real firework rocket
			}
			if (t == 200) {
				server.runOnServer(s -> {
					ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
					player.setDeltaMovement(player.getDeltaMovement().add(0.4, 0.3, 0));
					player.hurtMarked = true;
				});
			}
			if (p.freeze() && t == 160) {
				sim.freeze(1500);
			}
			ctx.waitTick();
			Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
			boolean gliding = ctx.computeOnClient(mc -> mc.player.isFallFlying());
			double speed = Math.hypot(now.x - last.x, now.z - last.z);
			if (gliding) {
				glidingTicks++;
				fastest = Math.max(fastest, speed);
				if (t >= 140 && t < 180) {
					boosted = Math.max(boosted, speed);
				}
				if (t >= 85 && t < 105) {
					climbed = Math.max(climbed, now.y - last.y);
				}
			}
			landed = !gliding && t > 250 && ctx.computeOnClient(mc -> mc.player.onGround());
			last = now;
		}
		ctx.waitTicks(p.rttMs() / 50 + 20);
		unequipElytra(ctx, server, arena);
		check(p.name() + ": the elytra really deployed", deployed);
		check(p.name() + ": really glided (" + glidingTicks + " ticks)", glidingTicks > 200);
		check(p.name() + String.format(Locale.ROOT, ": the dive really built up speed (%.2f blocks/tick)", fastest), fastest > 1.0);
		check(p.name() + String.format(Locale.ROOT, ": the pull-up really climbed (%.3f blocks/tick)", climbed), climbed > 0.02);
		check(p.name() + String.format(Locale.ROOT, ": the firework really boosted (%.2f blocks/tick)", boosted), boosted > 1.4);
		check(p.name() + ": landed after the glide", landed);
		return String.format(Locale.ROOT, "glide %d ticks, %.2f max, boost %.2f", glidingTicks, fastest, boosted);
	}

	// ---------------------------------------------------------------- legitimate mounts

	private int summonMount(TestDedicatedServerContext server, EntityType<? extends AbstractHorse> type, Vec3 pos, double speed, double jump) {
		return server.computeOnServer(s -> {
			AbstractHorse horse = type.create(s.overworld(), EntitySpawnReason.COMMAND);
			horse.snapTo(pos.x, pos.y, pos.z, 0, 0);
			horse.setTamed(true);
			horse.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
			if (speed > 0) {
				horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
			}
			if (jump > 0) {
				horse.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(jump);
			}
			s.overworld().addFreshEntity(horse);
			return horse.getId();
		});
	}

	private void mount(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, int id) {
		server.runOnServer(s -> {
			ServerPlayer player = s.getPlayerList().getPlayer(arena.player());
			player.startRiding(s.overworld().getEntity(id), true, true);
		});
		if (!waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.getVehicle() != null))) {
			throw new AssertionError("could not mount the test mount");
		}
		ctx.waitTicks(10);
	}

	private void dismountAndRemove(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, int id) {
		ctx.getInput().holdKeyFor(options -> options.keyShift, 3);
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.getVehicle() == null));
		for (int i = 0; i < 2; i++) {
			ctx.getInput().holdKeyFor(options -> options.keyJump, 2);
			ctx.waitTicks(12);
		}
		discard(server, id);
		heal(server, arena);
		teleportHome(ctx, server, arena);
	}

	/** Holds forward while steering around the arena centre; returns the fastest client speed of the mount. */
	private double ride(ClientGameTestContext ctx, Arena arena, int ticks, double radius, int jumpEvery, boolean sprint) {
		ctx.getInput().holdKey(options -> options.keyUp);
		if (sprint) {
			ctx.getInput().holdKey(options -> options.keySprint);
		}
		double fastest = 0;
		Vec3 last = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? Vec3.ZERO : mc.player.getVehicle().position());
		for (int i = 0; i < ticks; i++) {
			ctx.runOnClient(mc -> mc.player.setYRot(orbitYaw(mc, arena, radius)));
			if (jumpEvery > 0 && i % jumpEvery == 0 && i > 0) {
				ctx.getInput().holdKeyFor(options -> options.keyJump, 10); // a full jump bar, released after 10 ticks
				i += 10;
				// holdKeyFor waits those 10 ticks: measure per tick again from here, not across the whole hold.
				last = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? Vec3.ZERO : mc.player.getVehicle().position());
			}
			ctx.waitTick();
			Vec3 now = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? Vec3.ZERO : mc.player.getVehicle().position());
			fastest = Math.max(fastest, Math.hypot(now.x - last.x, now.z - last.z));
			last = now;
		}
		ctx.getInput().releaseKey(options -> options.keyUp);
		ctx.getInput().releaseKey(options -> options.keySprint);
		return fastest;
	}

	/**
	 * Real input on the fastest possible horse (speed 0.3375, jump 1.0): galloping in circles with full jumps,
	 * on packed ice, with Speed II removed by the server mid-gallop, over a 1-block step and off a 4-block ledge.
	 */
	private String legitimateHorse(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, LatencySimulator sim,
			Profile p) {
		teleportHome(ctx, server, arena);
		int id = summonMount(server, EntityType.HORSE, arena.feet().add(10, 0, 0), 0.3375, 1.0);
		teleport(ctx, server, arena, arena.feet().add(10, 0, 1), 0);
		mount(ctx, server, arena, id);
		double startY = ctx.computeOnClient(mc -> mc.player.getVehicle().getY());
		double peak = startY;
		// Gallop with full jumps.
		double gallop = ride(ctx, arena, 120, 10, 30, false);
		for (int i = 0; i < 20; i++) {
			peak = Math.max(peak, ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? 0 : mc.player.getVehicle().getY()));
			ctx.waitTick();
		}
		// Jump in place, recording the height.
		ctx.getInput().holdKeyFor(options -> options.keyJump, 10);
		for (int i = 0; i < 25; i++) {
			peak = Math.max(peak, ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? 0 : mc.player.getVehicle().getY()));
			ctx.waitTick();
		}
		// Speed II, removed by the server while galloping (the client keeps it for a round trip); a freeze in the jitter profile.
		String horseUuid = server.computeOnServer(s -> s.overworld().getEntity(id).getUUID().toString());
		server.runCommand("effect give " + horseUuid + " minecraft:speed 30 1 true");
		ctx.getInput().holdKey(options -> options.keyUp);
		for (int i = 0; i < 80; i++) {
			if (i == 40) {
				server.runCommand("effect clear " + horseUuid);
				if (p.freeze()) {
					sim.freeze(1500);
				}
			}
			ctx.runOnClient(mc -> mc.player.setYRot(orbitYaw(mc, arena, 10)));
			ctx.waitTick();
		}
		ctx.getInput().releaseKey(options -> options.keyUp);
		ctx.waitTicks(p.rttMs() / 50 + 20);
		// Packed ice ring.
		fill(server, arena.x() - 14, arena.y(), arena.z() - 14, arena.x() + 14, arena.y(), arena.z() + 14, "packed_ice");
		double ice = ride(ctx, arena, 80, 10, 0, false);
		fill(server, arena.x() - 14, arena.y(), arena.z() - 14, arena.x() + 14, arena.y(), arena.z() + 14, "stone");
		ctx.waitTicks(20);
		// A 1-block step and a 4-block ledge: a 9×9 tower east of the centre, walked up and ridden off.
		int tx = arena.x() + 20;
		fill(server, tx, arena.y() + 1, arena.z() - 4, tx + 8, arena.y() + 4, arena.z() + 4, "stone");
		fill(server, tx - 1, arena.y() + 1, arena.z() - 1, tx - 1, arena.y() + 1, arena.z() + 1, "stone");
		dismountAndRemove(ctx, server, arena, id);
		int id2 = summonMount(server, EntityType.HORSE, new Vec3(tx + 4.5, arena.y() + 5, arena.z() + 0.5), 0.3375, 1.0);
		teleport(ctx, server, arena, new Vec3(tx + 4.5, arena.y() + 5, arena.z() + 1.5), 90);
		mount(ctx, server, arena, id2);
		ctx.runOnClient(mc -> mc.player.setYRot(90)); // west, off the tower's edge
		ctx.getInput().holdKeyFor(options -> options.keyUp, 30);
		double lowest = ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? 0 : mc.player.getVehicle().getY());
		for (int i = 0; i < 30; i++) {
			lowest = Math.min(lowest, ctx.computeOnClient(mc -> mc.player.getVehicle() == null ? 999 : mc.player.getVehicle().getY()));
			ctx.waitTick();
		}
		// Back east: up the 1-block step without jumping.
		ctx.runOnClient(mc -> mc.player.setYRot(-90));
		ctx.getInput().holdKeyFor(options -> options.keyUp, 30);
		ctx.waitTicks(p.rttMs() / 50 + 20);
		fill(server, tx - 1, arena.y() + 1, arena.z() - 4, tx + 8, arena.y() + 4, arena.z() + 4, "air");
		dismountAndRemove(ctx, server, arena, id2);
		double jump = peak - startY;
		check(p.name() + String.format(Locale.ROOT, ": the horse really galloped (%.3f blocks/tick)", gallop), gallop > 0.5);
		check(p.name() + String.format(Locale.ROOT, ": the horse really jumped high (%.2f blocks)", jump), jump > 3.0);
		check(p.name() + String.format(Locale.ROOT, ": the horse really ran on ice (%.3f blocks/tick)", ice), ice > 0.5);
		check(p.name() + String.format(Locale.ROOT, ": the horse really rode off the ledge (down to %.1f)", lowest), lowest < arena.y() + 2);
		return String.format(Locale.ROOT, "horse %.2f, jump %.2f, ice %.2f", gallop, jump, ice);
	}

	/** Real input: a camel walking with sprint held, and dashing. */
	private String legitimateCamel(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena) {
		teleportHome(ctx, server, arena);
		int id = summonMount(server, EntityType.CAMEL, arena.feet().add(10, 0, 0), 0, 0);
		teleport(ctx, server, arena, arena.feet().add(10, 0, 2), 0);
		mount(ctx, server, arena, id);
		ctx.waitTicks(40); // a camel takes a moment to stand up
		double fastest = ride(ctx, arena, 160, 12, 60, true);
		ctx.waitTicks(20);
		dismountAndRemove(ctx, server, arena, id);
		check(String.format(Locale.ROOT, "the camel really dashed (%.2f blocks/tick)", fastest), fastest > 0.6);
		return String.format(Locale.ROOT, "camel %.2f", fastest);
	}

	// ---------------------------------------------------------------- elytra cheats

	/**
	 * The real client glides, then forces its velocity every tick: "hover" keeps its height at 1.5 blocks/tick,
	 * "climb" rises 0.3 per tick, "boost" adds 0.06 forward speed per tick (up to 2.4) like a firework without a rocket.
	 */
	private String elytraCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p, String kind) {
		equipElytra(ctx, server, arena);
		boolean deployed = takeOff(ctx, server, arena, new Vec3(arena.x() + 14.5, arena.y() + 45, arena.z() + 0.5), 0);
		ctx.waitTicks(10);
		resetViolations(server, arena);
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < (kind.equals("boost") ? 60 : 100); i++) {
			ctx.runOnClient(mc -> {
				mc.player.setYRot(orbitYaw(mc, arena, 14));
				mc.player.setXRot(0);
				Vec3 look = mc.player.getLookAngle();
				double h = Math.hypot(look.x, look.z);
				Vec3 v = mc.player.getDeltaMovement();
				double speed = Math.min(2.4, Math.hypot(v.x, v.z) + 0.06);
				switch (kind) {
					case "hover" -> mc.player.setDeltaMovement(look.x / h * 1.5, 0.0196, look.z / h * 1.5);
					case "climb" -> mc.player.setDeltaMovement(look.x / h * 1.0, 0.33, look.z / h * 1.0);
					default -> mc.player.setDeltaMovement(look.x / h * speed, v.y, look.z / h * speed);
				}
			});
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(ElytraCheck.ID)).toList();
		check(p.name() + ": elytra deployed for the " + kind + " cheat", deployed);
		check(p.name() + ": elytra " + kind + " without fireworks detected", !got.isEmpty());
		check(p.name() + ": elytra flags are MEDIUM only", got.stream().allMatch(f -> f.confidence() == Confidence.MEDIUM));
		teleport(ctx, server, arena, arena.feet(), 0);
		unequipElytra(ctx, server, arena);
		resetViolations(server, arena);
		return got.isEmpty() ? "missed" : (got.get(0).nanos() - t0) / 1_000_000 + " ms";
	}

	// ---------------------------------------------------------------- horse cheats

	private static void setVehicleVelocity(Minecraft mc, double x, double y, double z) {
		Entity vehicle = mc.player.getVehicle();
		if (vehicle != null) {
			vehicle.setDeltaMovement(x, y, z);
		}
	}

	/** The real client keeps its horse at a constant vertical speed {@code vy} in the air. */
	private String horseFlyCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p, String label,
			double vy) {
		teleportHome(ctx, server, arena);
		int id = summonMount(server, EntityType.HORSE, arena.feet().add(5, 0, 0), 0.25, 0.7);
		teleport(ctx, server, arena, arena.feet().add(5, 0, 1), 0);
		mount(ctx, server, arena, id);
		resetViolations(server, arena);
		for (int i = 0; i < 4; i++) {
			ctx.runOnClient(mc -> setVehicleVelocity(mc, 0, 0.4, 0));
			ctx.waitTick();
		}
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < 40; i++) {
			ctx.runOnClient(mc -> setVehicleVelocity(mc, 0, vy, 0));
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(HorseCheck.ID)).toList();
		check(p.name() + ": horse " + label + " detected", !got.isEmpty());
		check(p.name() + ": horse flags are MEDIUM only", got.stream().allMatch(f -> f.confidence() == Confidence.MEDIUM));
		dismountAndRemove(ctx, server, arena, id);
		resetViolations(server, arena);
		return got.isEmpty() ? "missed" : (got.get(0).nanos() - t0) / 1_000_000 + " ms";
	}

	/**
	 * The real client's horse (jump strength 0.7) takes off at 1.1 blocks/tick, five times (a fall from that
	 * height does not hurt a horse or its rider).
	 */
	private String horseHighJumpCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int id = summonMount(server, EntityType.HORSE, arena.feet().add(5, 0, 0), 0.25, 0.7);
		teleport(ctx, server, arena, arena.feet().add(5, 0, 1), 0);
		mount(ctx, server, arena, id);
		resetViolations(server, arena);
		int start = flags.size();
		for (int i = 0; i < 5; i++) {
			waitUntil(ctx, 80, () -> ctx.computeOnClient(mc -> mc.player.getVehicle() != null && mc.player.getVehicle().onGround()));
			ctx.runOnClient(mc -> setVehicleVelocity(mc, 0, 1.1, 0));
			ctx.waitTicks(40);
			heal(server, arena);
		}
		ctx.waitTicks(p.rttMs() / 50 + 10);
		List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(HorseCheck.ID) && f.reason().equals("too_high")).toList();
		check(p.name() + ": horse high jump flagged on every jump (" + got.size() + "/5)", got.size() == 5);
		dismountAndRemove(ctx, server, arena, id);
		resetViolations(server, arena);
		return got.size() + "/5";
	}

	/** The real client's horse (speed 0.25, about 0.55 blocks/tick at most) is forced to 1.2 blocks/tick on the ground. */
	private String horseSpeedCheat(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int id = summonMount(server, EntityType.HORSE, arena.feet().add(-20, 0, 0), 0.25, 0.7);
		teleport(ctx, server, arena, arena.feet().add(-20, 0, 1), -90);
		mount(ctx, server, arena, id);
		resetViolations(server, arena);
		int start = flags.size();
		long t0 = System.nanoTime();
		for (int i = 0; i < 30; i++) {
			ctx.runOnClient(mc -> {
				Entity v = mc.player.getVehicle();
				if (v != null) {
					v.setDeltaMovement(1.2, v.getDeltaMovement().y, 0);
				}
			});
			ctx.waitTick();
		}
		ctx.waitTicks(p.rttMs() / 50 + 5);
		List<FlagEvent> got = flagsSince(start).stream().filter(f -> f.check().equals(HorseCheck.ID)).toList();
		check(p.name() + ": horse speed 1.2 blocks/tick detected", got.stream().anyMatch(f -> f.reason().equals("too_fast")));
		dismountAndRemove(ctx, server, arena, id);
		resetViolations(server, arena);
		return got.isEmpty() ? "missed" : (got.get(0).nanos() - t0) / 1_000_000 + " ms";
	}

	// ---------------------------------------------------------------- mitigation

	private static int spawnPig(TestDedicatedServerContext server, Vec3 pos) {
		return server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			Mob pig = (Mob) EntityType.PIG.create(level, EntitySpawnReason.COMMAND);
			pig.snapTo(pos.x, pos.y, pos.z, 0, 0);
			pig.setNoAi(true);
			level.addFreshEntity(pig);
			return pig.getId();
		});
	}

	private static boolean hurt(TestDedicatedServerContext server, int id) {
		return server.computeOnServer(s -> s.overworld().getEntity(id) instanceof LivingEntity e && e.getHealth() < e.getMaxHealth());
	}

	private void setMitigation(boolean on) {
		ConfigHandle config = config();
		config.set(AcSettings.ENFORCEMENT_MITIGATION, on ? AcSettings.MitigationMode.ON : AcSettings.MitigationMode.OFF);
	}

	private boolean mitigated(int fromIndex, String checkId, String action) {
		return flagsSince(fromIndex).stream().anyMatch(f -> f.check().equals(checkId) && f.mitigation().equals(action));
	}

	/**
	 * Crafted attacks: a hit at 4.2 blocks (reach HIGH) hurts the pig without mitigation and is cancelled with
	 * it; the second of two targets in one tick is cancelled; after an attack while looking away (hitbox HIGH,
	 * judged after vanilla processed it), the next attack is cancelled, and one a second later is not.
	 */
	private String combatMitigation(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int wait = p.rttMs() / 50 + 15;
		List<String> result = new ArrayList<>();

		// Reach: control without mitigation, then with it.
		ctx.runOnClient(mc -> {
			mc.player.setYRot(-90); // facing +X, at the pigs
			mc.player.setXRot(10);
		});
		setMitigation(false);
		int control = spawnPig(server, arena.feet().add(4.2 + 0.45, 0, 0));
		ctx.waitTicks(wait);
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(control)));
		ctx.waitTicks(wait);
		boolean controlHurt = hurt(server, control);
		discard(server, control);
		setMitigation(true);
		resetViolations(server, arena);
		int far = spawnPig(server, arena.feet().add(4.2 + 0.45, 0, 0));
		ctx.waitTicks(wait);
		int start = flags.size();
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(far)));
		ctx.waitTicks(wait);
		boolean farHurt = hurt(server, far);
		discard(server, far);
		check(p.name() + ": without mitigation a 4.2-block hit hurts (premise)", controlHurt);
		check(p.name() + ": reach mitigation cancels the hit", !farHurt && mitigated(start, ReachCheck.ID, "cancel_attack"));
		result.add(!farHurt ? "reach cancelled" : "reach NOT cancelled");

		// Multi-target: the second target in one tick is not hit.
		ctx.waitTicks(30);
		int a = spawnPig(server, arena.feet().add(2.0, 0, 0));
		int b = spawnPig(server, arena.feet().add(-2.0, 0, 0));
		ctx.runOnClient(mc -> {
			mc.player.setYRot(-90); // facing +X, at pig a
			mc.player.setXRot(35);
		});
		ctx.waitTicks(wait);
		start = flags.size();
		ctx.runOnClient(mc -> {
			mc.getConnection().send(new ServerboundAttackPacket(a));
			mc.getConnection().send(new ServerboundAttackPacket(b));
		});
		ctx.waitTicks(wait);
		boolean bHurt = hurt(server, b);
		check(p.name() + ": multi-target mitigation cancels the second hit", !bHurt && mitigated(start, MultiTargetCheck.ID, "cancel_attack"));
		result.add(!bHurt ? "multi cancelled" : "multi NOT cancelled");
		discard(server, a);
		discard(server, b);

		// Hitbox: an attack while looking away is flagged after vanilla processed it; the next attack is cancelled.
		ctx.waitTicks(30);
		resetViolations(server, arena);
		int seen = spawnPig(server, arena.feet().add(2.0, 0, 0));
		int next = spawnPig(server, arena.feet().add(0, 0, 2.0));
		ctx.runOnClient(mc -> {
			mc.player.setYRot(90); // facing -X, away from both
			mc.player.setXRot(0);
		});
		ctx.waitTicks(wait);
		start = flags.size();
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(seen)));
		ctx.waitTicks(2);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(0); // now facing +Z, at the second pig
			mc.player.setXRot(35);
		});
		ctx.waitTick();
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(next)));
		ctx.waitTicks(wait);
		boolean nextHurtEarly = hurt(server, next);
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> mc.getConnection().send(new ServerboundAttackPacket(next)));
		ctx.waitTicks(wait);
		boolean nextHurtLater = hurt(server, next);
		check(p.name() + ": hitbox mitigation blocks the next attack", mitigated(start, HitboxCheck.ID, "block_attacks") && !nextHurtEarly);
		check(p.name() + ": attacks work again after the hitbox block", nextHurtLater);
		result.add(!nextHurtEarly && nextHurtLater ? "hitbox blocks 0.5 s" : "hitbox wrong");
		discard(server, seen);
		discard(server, next);
		resetViolations(server, arena);
		teleportHome(ctx, server, arena);
		return String.join(", ", result);
	}

	/**
	 * Crafted block actions: breaking a slime block (instant) 5.15 blocks away breaks it without mitigation and
	 * is cancelled with it; a dirt block "finished" after 3 of 15 ticks stays.
	 */
	private String blockMitigation(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		teleportHome(ctx, server, arena);
		int wait = p.rttMs() / 50 + 15;
		BlockPos slime = new BlockPos(arena.x() + 5, arena.y() + 2, arena.z() + 3); // 5.15 blocks from the eye
		List<String> result = new ArrayList<>();

		setMitigation(false);
		setblock(server, slime.getX(), slime.getY(), slime.getZ(), "slime_block");
		ctx.waitTicks(wait);
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, slime, Direction.UP)));
		ctx.waitTicks(wait);
		boolean controlBroken = server.computeOnServer(s -> s.overworld().getBlockState(slime).isAir());
		setMitigation(true);
		resetViolations(server, arena);
		setblock(server, slime.getX(), slime.getY(), slime.getZ(), "slime_block");
		ctx.waitTicks(wait);
		int start = flags.size();
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, slime, Direction.UP)));
		ctx.waitTicks(wait);
		boolean kept = server.computeOnServer(s -> s.overworld().getBlockState(slime).is(Blocks.SLIME_BLOCK));
		check(p.name() + ": without mitigation the far slime block breaks (premise)", controlBroken);
		check(p.name() + ": block reach mitigation keeps the block", kept && mitigated(start, BlockReachCheck.ID, "cancel_action"));
		result.add(kept ? "block reach cancelled" : "block reach NOT cancelled");
		setblock(server, slime.getX(), slime.getY(), slime.getZ(), "air");

		BlockPos dirt = new BlockPos(arena.x() + 2, arena.y() + 1, arena.z());
		setblock(server, dirt.getX(), dirt.getY(), dirt.getZ(), "dirt");
		ctx.waitTicks(wait);
		start = flags.size();
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, dirt, Direction.WEST)));
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, dirt, Direction.WEST)));
		ctx.waitTicks(wait + 30); // vanilla's delayed destroy would have broken it by now
		boolean dirtKept = server.computeOnServer(s -> s.overworld().getBlockState(dirt).is(Blocks.DIRT));
		ctx.runOnClient(mc -> mc.getConnection().send(
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, dirt, Direction.WEST)));
		check(p.name() + ": fast break mitigation keeps the block", dirtKept && mitigated(start, FastBreakCheck.ID, "cancel_action"));
		result.add(dirtKept ? "fast break cancelled" : "fast break NOT cancelled");
		ctx.waitTicks(10);
		setblock(server, dirt.getX(), dirt.getY(), dirt.getZ(), "air");
		server.runCommand("kill @e[type=item]");
		resetViolations(server, arena);
		return String.join(", ", result);
	}

	/**
	 * A real client forced to fly up at 0.2 blocks/tick, and to run at 0.5 blocks/tick: once the flag is HIGH, the
	 * server sets it back to where it was before the cheat.
	 */
	private String setbackMitigation(ClientGameTestContext ctx, TestDedicatedServerContext server, Arena arena, Profile p) {
		List<String> result = new ArrayList<>();
		// Fly up.
		teleportHome(ctx, server, arena);
		Vec3 home = arena.feet();
		server.runCommand(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", arena.name(), home.x, home.y + 2, home.z));
		waitUntil(ctx, 100, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(home.add(0, 2, 0)) < 0.5));
		resetViolations(server, arena);
		int start = flags.size();
		double maxDrop = 0;
		double previous = ctx.computeOnClient(mc -> mc.player.getY());
		for (int i = 0; i < 40 + p.rttMs() / 50; i++) {
			ctx.runOnClient(mc -> mc.player.setDeltaMovement(0, 0.2, 0));
			ctx.waitTick();
			double y = ctx.computeOnClient(mc -> mc.player.getY());
			maxDrop = Math.max(maxDrop, previous - y);
			previous = y;
		}
		boolean flySetback = mitigated(start, FlyCheck.ID, "setback");
		check(p.name() + String.format(Locale.ROOT, ": flying up is set back (dropped %.2f blocks at once)", maxDrop),
				flySetback && maxDrop > 2.0);
		result.add(flySetback ? String.format(Locale.ROOT, "fly set back %.1f", maxDrop) : "fly NOT set back");
		ctx.waitTicks(40);
		heal(server, arena);

		// Run too fast.
		Vec3 begin = arena.feet().add(-15, 0, 0);
		teleport(ctx, server, arena, begin, -90);
		resetViolations(server, arena);
		start = flags.size();
		double maxBack = 0;
		double previousX = ctx.computeOnClient(mc -> mc.player.getX());
		for (int i = 0; i < 40 + p.rttMs() / 50; i++) {
			ctx.runOnClient(mc -> mc.player.setDeltaMovement(0.5, mc.player.getDeltaMovement().y, 0));
			ctx.waitTick();
			double x = ctx.computeOnClient(mc -> mc.player.getX());
			maxBack = Math.max(maxBack, previousX - x);
			previousX = x;
		}
		boolean speedSetback = mitigated(start, SpeedCheck.ID, "setback");
		check(p.name() + String.format(Locale.ROOT, ": running too fast is set back (%.2f blocks back at once)", maxBack),
				speedSetback && maxBack > 3.0);
		result.add(speedSetback ? String.format(Locale.ROOT, "speed set back %.1f", maxBack) : "speed NOT set back");
		ctx.waitTicks(20);
		teleportHome(ctx, server, arena);
		resetViolations(server, arena);
		return String.join(", ", result);
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

	private List<FlagEvent> flagsSince(int fromIndex) {
		List<FlagEvent> all = List.copyOf(flags);
		return all.subList(Math.min(fromIndex, all.size()), all.size());
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
		lines.add("| Profile | Measured RTT (ms) | Legitimate-play flags | Elytra hover / climb / boost (first flag) | Horse hover / climb / high jump / speed | Combat mitigation | Block mitigation | Setbacks |");
		lines.add("|---|---|---|---|---|---|---|---|");
		lines.addAll(table);
		for (String line : lines) {
			LOG.info("[MejtaAcTest] TABLE6 {}", line);
		}
		try {
			Files.write(FabricLoader.getInstance().getGameDir().resolve("mejtaac-stage6-latency.md"), lines);
		} catch (IOException e) {
			LOG.error("[MejtaAcTest] could not write results table", e);
		}
	}
}
