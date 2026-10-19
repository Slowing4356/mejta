package com.mejta.mejtaac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtaac.AcSettings.LogLevel;
import com.mejta.mejtaac.alert.AlertPublisher;
import com.mejta.mejtaac.check.Flag;
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
import com.mejta.mejtaac.check.protocol.InvalidMoveCheck;
import com.mejta.mejtaac.check.protocol.MoveData;
import com.mejta.mejtaac.check.vehicle.BoatFlyCheck;
import com.mejta.mejtaac.check.vehicle.HorseCheck;
import com.mejta.mejtaac.enforce.EnforcementPolicy;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;
import com.mejta.mejtaac.enforce.Setbacks;
import com.mejta.mejtaac.mixin.ServerGamePacketListenerImplAccessor;
import com.mejta.mejtaac.record.AcRecords;
import com.mejta.mejtaac.sync.EntityHistory;
import com.mejta.mejtaac.sync.TickSync;
import com.mejta.mejtaac.violation.Throttle;
import com.mejta.mejtaac.violation.ViolationTracker;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.metrics.Metrics;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Runs the pipeline for every piece of evidence: detect (checks) → assess (violation level,
 * exemptions) → decide (enforcement policy) → report (log, record, alert) → act (kick, and, if
 * {@code enforcement.mitigation} is on, undo the flagged action: setback, cancelled hit or block action).
 * Each stage is configurable on its own and a failure in one never breaks packet handling.
 */
public final class AntiCheat {
	// Cost of each hook, shown by MejtaPerf (/mperf metrics mejtaac). Two clock reads per call.
	private static final Metrics.Timer T_MOVE_PACKET = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.move");
	private static final Metrics.Timer T_SERVER_TICK_END = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.server_tick");
	private static final Metrics.Timer T_PONG = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.pong");
	private static final Metrics.Timer T_CLIENT_TICK_ARRIVAL = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.client_tick_arrival");
	private static final Metrics.Timer T_CLIENT_TICK_END = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.client_tick");
	private static final Metrics.Timer T_ATTACK = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.attack");
	private static final Metrics.Timer T_BLOCK_INTERACTION = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.block_interaction");
	private static final Metrics.Timer T_PLAYER_ACTION = MejtaCoreAPI.metrics().timer(MejtaAC.ID, "hook.player_action");

	/** At most one violation record and one log line per player and check per second. */
	private static final long RECORD_INTERVAL_MS = 1_000;
	/** Client ticks after leaving a vehicle in which movement is not predicted (dismount placement). */
	private static final int PASSENGER_GRACE_TICKS = 10;
	/**
	 * Client ticks after an elytra glide in which horizontal speed is not predicted: the client can keep
	 * gliding (decay 0.99) for a tick or two after the server already sees it on the ground.
	 */
	private static final int GLIDE_GRACE_TICKS = 5;
	/** Client ticks after mounting a boat before its movement is predicted. */
	private static final int MOUNT_GRACE_TICKS = 3;
	/** How long a lost jump boost is still allowed, on top of twice the recent round trip. */
	private static final long JUMP_POWER_GRACE_MS = 2_000;
	/** How long a teleport or respawn counts as unconfirmed when tick sync is unavailable. */
	private static final long UNSYNCED_TELEPORT_NANOS = 1_000_000_000L;
	/** Vanilla's margin beyond the block interaction range before it refuses a block action. */
	private static final double BLOCK_RANGE_MARGIN = 1.0;
	/** Client ticks after mounting a horse-like mount before its movement is predicted. */
	private static final int HORSE_MOUNT_GRACE_TICKS = 5;
	/** How long a firework boost, or support under a mount, counts as possibly seen when tick sync is unavailable. */
	private static final long UNSYNCED_WINDOW_NANOS = 2_000_000_000L;
	/** Mitigation of a hitbox flag: the hit itself was already processed, so the next attacks are cancelled. */
	private static final int HITBOX_ATTACK_BLOCK_TICKS = 10;

	/** What a mitigation did, for records and alerts; {@code cancel} stops vanilla processing the packet. */
	record Mitigated(String action, boolean cancel) {
	}

	/** Undoes the action behind a flag; returns null if nothing could be done. Runs on the server thread. */
	@FunctionalInterface
	interface Mitigator {
		@Nullable Mitigated apply();
	}

	/** Combat flags decided while the attack packet is being handled: vanilla never processes the hit. */
	private static final Mitigator CANCEL_ATTACK = () -> new Mitigated("cancel_attack", true);

	private static volatile AntiCheat instance;

	/**
	 * Development diagnostics, off in production: logs every judged boat tick and every take-off with
	 * the reason it was or was not trusted. Set by tests investigating false positives.
	 */
	public static volatile boolean traceMovement;

	private final Logger log;
	private final ViolationTracker violations = new ViolationTracker();
	private final Throttle recordThrottle = new Throttle();
	private final Throttle alertThrottle = new Throttle();
	private final InvalidMoveCheck invalidMove = new InvalidMoveCheck();
	private final TickSync sync = new TickSync();
	private final EntityHistory history = new EntityHistory();
	private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
	private final List<Consumer<Detection>> flagListeners = new CopyOnWriteArrayList<>();
	private final AcRecords records;
	private final AlertPublisher alerts;
	private volatile AcConfig config;

	AntiCheat(String addonId, ConfigHandle handle, AcRecords records, Logger log) {
		this.log = log;
		this.records = records;
		this.alerts = new AlertPublisher(addonId);
		this.config = AcConfig.from(handle);
		handle.addChangeListener(() -> onConfigChanged(AcConfig.from(handle)));
	}

	static void install(AntiCheat antiCheat) {
		instance = antiCheat;
	}

	/** The running instance, or empty if MejtaAC failed to initialize. */
	public static Optional<AntiCheat> get() {
		return Optional.ofNullable(instance);
	}

	public AcConfig config() {
		return config;
	}

	public ViolationTracker violations() {
		return violations;
	}

	public AcRecords records() {
		return records;
	}

	/**
	 * Observes every flag after assessment, before rate limiting (exempt players are not reported).
	 * Called on the server thread; listeners must be fast. Used by tests and diagnostics.
	 */
	public void addFlagListener(Consumer<Detection> listener) {
		flagListeners.add(listener);
	}

	public TickSync sync() {
		return sync;
	}

	public EntityHistory history() {
		return history;
	}

	/** Per-player check state. Fields touched on the network thread are thread-safe. */
	static final class PlayerData {
		final TimerCheck.State timer = new TimerCheck.State();
		final Queue<Flag> pendingTimerFlags = new ConcurrentLinkedQueue<>();
		final MultiTargetCheck.State multiTarget = new MultiTargetCheck.State();
		final GroundSpoofCheck.State ground = new GroundSpoofCheck.State();
		volatile boolean claimedOnGround = true;
		final FlyCheck.State fly = new FlyCheck.State();
		double lastTickY = Double.NaN;
		/** Server tick after the newest velocity change sent to this player (knockback, explosion...). */
		volatile int impulseTick = Integer.MIN_VALUE;
		volatile long impulseNanos;
		boolean impulsePending;
		/** Latest raw rotation from a movement packet, and whether one arrived this client tick. */
		float yaw;
		float pitch;
		final List<PendingAttack> pendingAttacks = new ArrayList<>();
		final SpeedCheck.State speed = new SpeedCheck.State();
		double lastTickX = Double.NaN;
		double lastTickZ = Double.NaN;
		/** Server-side ground state at the end of the last two client ticks. */
		boolean groundOneAgo;
		boolean groundTwoAgo;
		float frictionOneAgo = 0.6F;
		float frictionTwoAgo = 0.6F;
		/** Client ticks seen so far (tick-end packets), a latency-proof clock. */
		long clientTicks;
		BlockPos breakPos;
		long breakStartTicks;
		double breakProgress;
		volatile boolean singleplayerOwner;
		volatile float msPerTick;
		/** Client ticks since the player last rode a vehicle (large when never). */
		int ticksSincePassenger = Integer.MAX_VALUE / 2;
		/** Client ticks since the player last glided with an elytra (large when never). */
		int ticksSinceGliding = Integer.MAX_VALUE / 2;
		final JumpCheck.State jump = new JumpCheck.State();
		/** Ground (server-side or claimed) at the end of the last client tick. */
		boolean jumpGroundBefore = true;
		/** Whether the player stood on a slime block or bed at the end of the last grounded tick. */
		boolean bouncyBelow;
		/** Vertical movement of the last four client ticks, for slime and bed bounces. */
		final double[] recentDy = new double[4];
		/** Highest jump power of the recent past (see JumpCheck) and until when it is kept. */
		double jumpPowerMax;
		long jumpPowerMaxUntilNanos;
		final BoatFlyCheck.State boatFly = new BoatFlyCheck.State();
		int vehicleId = -1;
		int vehicleTicks;
		double lastVehicleY = Double.NaN;
		/** Server tick of the newest correction of the player's vehicle (rejected move, teleport, push). */
		volatile int vehicleImpulseTick = Integer.MIN_VALUE;
		volatile long vehicleImpulseNanos;
		volatile boolean vehicleImpulsePending;
		/** Server tick of the newest teleport or respawn sent to this player, until the client confirms it. */
		volatile int teleportTick = Integer.MIN_VALUE;
		volatile long teleportNanos;
		volatile boolean teleportPending;
		/** Whether a movement packet with a position arrived in the current client tick. */
		boolean positionThisTick;
		final ElytraCheck.State elytra = new ElytraCheck.State();
		double glideLastX = Double.NaN;
		double glideLastY = Double.NaN;
		double glideLastZ = Double.NaN;
		/** Server tick at whose end a firework rocket was last seen next to the gliding player. */
		volatile int rocketTick = Integer.MIN_VALUE;
		volatile long rocketNanos;
		final HorseCheck.State horse = new HorseCheck.State();
		int horseId = -1;
		int horseTicks;
		double horseLastX = Double.NaN;
		double horseLastY = Double.NaN;
		double horseLastZ = Double.NaN;
		boolean horseGroundOneAgo = true;
		boolean horseGroundTwoAgo = true;
		float horseFrictionOneAgo = 0.6F;
		float horseFrictionTwoAgo = 0.6F;
		/** Whether the mount stood on a slime block or bed at the end of its last grounded tick. */
		boolean horseBouncyBelow;
		/** Server tick at which the server last saw support under the mount. */
		int horseSupportTick = Integer.MIN_VALUE;
		long horseSupportNanos;
		/** Mitigation: the newest safe position (setback target), its level, and when the last setback was sent. */
		@Nullable Vec3 safePos;
		@Nullable Level safeLevel;
		long lastSetbackNanos = Long.MIN_VALUE;
		/** Client tick of the newest setback and of the newest movement flag (-1 if none). */
		long setbackTicks = -1;
		long movementFlagTicks = -1;
		/** Mitigation of combat.hitbox: attacks are cancelled until this client tick. */
		long attacksBlockedUntilTicks;
	}

	/** An attack waiting for its client tick to end, so the rotation the client used is known. */
	record PendingAttack(List<Vec3> eyes, List<AABB> candidates, HitboxCheck.Rotation atAttack, double rayLength,
			boolean synced) {
	}

	private PlayerData data(UUID player) {
		return players.computeIfAbsent(player, k -> new PlayerData());
	}

	// ---------------------------------------------------------------- evidence entry points (server thread)

	/**
	 * Called for every movement packet on the server thread, before vanilla handles it.
	 *
	 * @return {@code true} if the player was kicked and the packet should not be processed further
	 */
	public boolean onMovePacket(ServerPlayer player, ServerboundMovePlayerPacket packet) {
		long start = Metrics.Timer.start();
		try {
			return onMovePacketMeasured(player, packet);
		} finally {
			T_MOVE_PACKET.stop(start);
		}
	}

	private boolean onMovePacketMeasured(ServerPlayer player, ServerboundMovePlayerPacket packet) {
		AcConfig cfg = config;
		PlayerData pd = data(player.getUUID());
		pd.claimedOnGround = packet.isOnGround();
		if (packet.hasPosition()) {
			pd.positionThisTick = true;
		}
		if (packet.hasRotation()) {
			pd.yaw = packet.getYRot(0);
			pd.pitch = packet.getXRot(0);
		}
		if (!cfg.enabled() || !cfg.invalidMoveEnabled()) {
			return false;
		}
		MoveData move = new MoveData(packet.hasPosition(), packet.getX(0), packet.getY(0), packet.getZ(0),
				packet.hasRotation(), packet.getYRot(0), packet.getXRot(0));
		Optional<Flag> flag = invalidMove.evaluate(move);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.invalidMoveKickVl());
	}

	public void onJoin(ServerPlayer player, MinecraftServer server) {
		data(player.getUUID()).singleplayerOwner = server.isSingleplayerOwner(player.nameAndId());
	}

	public void onDisconnect(UUID player) {
		violations.reset(player);
		recordThrottle.forget(player);
		alertThrottle.forget(player);
		players.remove(player);
		sync.forget(player);
	}

	/**
	 * The server stopped. Clears all per-player and per-entity state: an integrated server started
	 * later in the same game counts ticks and entity IDs from the start again.
	 */
	public void onServerStopped() {
		players.clear();
		sync.clear();
		history.clear();
		violations.clear();
		recordThrottle.clear();
		alertThrottle.clear();
	}

	/** Server thread, end of every server tick: entity history and sync pings, if a check needs them. */
	public void onServerTickEnd(MinecraftServer server) {
		long start = Metrics.Timer.start();
		try {
			onServerTickEndMeasured(server);
		} finally {
			T_SERVER_TICK_END.stop(start);
		}
	}

	private void onServerTickEndMeasured(MinecraftServer server) {
		AcConfig cfg = config;
		if (cfg.needsSync()) {
			history.record(server, server.getTickCount());
			sync.sendPings(server);
		}
		if (cfg.enabled() && cfg.elytraEnabled()) {
			scanRockets(server);
		}
	}

	/**
	 * Remembers when a firework rocket was next to each gliding player. Done at the end of the server tick,
	 * after entities ticked: an attached rocket has just moved to the player's hand, and no movement packet
	 * has moved the player since.
	 */
	private void scanRockets(MinecraftServer server) {
		int tick = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerData d = player.isFallFlying() ? players.get(player.getUUID()) : null;
			if (d != null && !player.level().getEntities(player, player.getBoundingBox().inflate(3.0),
					e -> e.getType() == EntityType.FIREWORK_ROCKET).isEmpty()) {
				d.rocketTick = tick;
				d.rocketNanos = System.nanoTime();
			}
		}
	}

	/** Network thread: one of our pongs arrived. */
	public void onPongArrival(UUID player, long nanos) {
		sync.recordArrival(player, nanos);
	}

	/** Server thread, in packet order: one of our pongs. */
	public void onPong(ServerPlayer player, int pongId) {
		long start = Metrics.Timer.start();
		try {
			onPongMeasured(player, pongId);
		} finally {
			T_PONG.stop(start);
		}
	}

	private void onPongMeasured(ServerPlayer player, int pongId) {
		sync.onPong(player.getUUID(), pongId);
	}

	/** Network thread: a client tick ended (timer accounting uses the arrival time). */
	public void onClientTickArrival(UUID player, long nanos, MinecraftServer server) {
		long start = Metrics.Timer.start();
		try {
			onClientTickArrivalMeasured(player, nanos, server);
		} finally {
			T_CLIENT_TICK_ARRIVAL.stop(start);
		}
	}

	private void onClientTickArrivalMeasured(UUID player, long nanos, MinecraftServer server) {
		AcConfig cfg = config;
		PlayerData d = players.get(player);
		if (d == null || !cfg.enabled() || !cfg.timerEnabled() || d.singleplayerOwner) {
			return;
		}
		ServerTickRateManager rate = server.tickRateManager();
		if (!rate.runsNormally() || rate.isSprinting()) {
			d.timer.reset();
			return;
		}
		float msPerTick = rate.millisecondsPerTick();
		if (msPerTick != d.msPerTick) {
			d.timer.reset();
			d.msPerTick = msPerTick;
		}
		TimerCheck.onClientTick(d.timer, nanos, msPerTick, cfg.timerMaxAheadMs()).ifPresent(d.pendingTimerFlags::add);
	}

	/**
	 * Server thread: a client tick ended. Runs per-tick checks.
	 *
	 * @return {@code true} if the player was kicked
	 */
	public boolean onClientTickEnd(ServerPlayer player) {
		long start = Metrics.Timer.start();
		try {
			return onClientTickEndMeasured(player);
		} finally {
			T_CLIENT_TICK_END.stop(start);
		}
	}

	private boolean onClientTickEndMeasured(ServerPlayer player) {
		AcConfig cfg = config;
		PlayerData d = data(player.getUUID());
		d.clientTicks++;
		boolean moved = d.positionThisTick;
		d.positionThisTick = false;
		MultiTargetCheck.onClientTickEnd(d.multiTarget);
		if (!cfg.enabled()) {
			d.pendingTimerFlags.clear();
			return false;
		}
		Flag timerFlag;
		while ((timerFlag = d.pendingTimerFlags.poll()) != null) {
			if (cfg.timerEnabled() && handle(player, timerFlag, cfg, cfg.timerKickVl())) {
				return true;
			}
		}
		if (cfg.groundSpoofEnabled()) {
			boolean exempt = groundExempt(player);
			boolean below = exempt || hasGroundBelow(player);
			Optional<Flag> flag = GroundSpoofCheck.onClientTick(d.ground, d.claimedOnGround, below, exempt,
					sync.recentMaxRttMs(player.getUUID()), player.getY(), player.level().getServer().getTickCount(),
					sync.lastAckTick(player.getUUID()), System.nanoTime());
			if (flag.isPresent() && handle(player, flag.get(), cfg, cfg.groundSpoofKickVl())) {
				return true;
			}
		}
		if (evaluatePendingAttacks(player, d, cfg)) {
			return true;
		}
		boolean impulse = impulseUntrusted(player, d);
		d.ticksSincePassenger = player.isPassenger() ? 0 : Math.min(d.ticksSincePassenger + 1, Integer.MAX_VALUE / 2);
		d.ticksSinceGliding = player.isFallFlying() ? 0 : Math.min(d.ticksSinceGliding + 1, Integer.MAX_VALUE / 2);
		double y = player.getY();
		double dy = Double.isNaN(d.lastTickY) ? 0 : y - d.lastTickY;
		d.lastTickY = y;
		if (cfg.jumpEnabled() && evaluateJump(player, d, cfg, impulse, dy)) {
			return true;
		}
		// A setback sent in this tick moved the server-side player: like a teleport, the rest of the tick is not judged.
		impulse |= d.setbackTicks == d.clientTicks;
		// Moving pistons push players in any direction; looked up once for fly and speed.
		boolean piston = !impulse && (cfg.flyEnabled() || cfg.speedEnabled())
				&& nearPiston(player, player.getBoundingBox().inflate(1.5));
		if (cfg.flyEnabled() && evaluateFly(player, d, cfg, impulse || piston, dy)) {
			return true;
		}
		impulse |= d.setbackTicks == d.clientTicks;
		if (cfg.speedEnabled() && evaluateSpeed(player, d, cfg, impulse || piston)) {
			return true;
		}
		impulse |= d.setbackTicks == d.clientTicks;
		if (cfg.elytraEnabled() && evaluateElytra(player, d, cfg, impulse, moved)) {
			return true;
		}
		updateSafePosition(player, d, cfg);
		if (cfg.boatFlyEnabled() && evaluateBoatFly(player, d, cfg)) {
			return true;
		}
		return cfg.horseEnabled() && evaluateHorse(player, d, cfg);
	}

	/**
	 * Server thread: the client attacked an entity, before vanilla handles it.
	 *
	 * @return {@code true} if the attack must not be processed: the player was kicked, or the attack is
	 *         mitigated (cancelled)
	 */
	public boolean onAttack(ServerPlayer player, int entityId) {
		long start = Metrics.Timer.start();
		try {
			return onAttackMeasured(player, entityId);
		} finally {
			T_ATTACK.stop(start);
		}
	}

	private boolean onAttackMeasured(ServerPlayer player, int entityId) {
		AcConfig cfg = config;
		if (!cfg.enabled() || player.isSpectator()) {
			return false;
		}
		PlayerData d = data(player.getUUID());
		// Mitigated combat.hitbox flag: that hit was judged after vanilla processed it, so the next ones are cancelled.
		boolean blocked = cfg.mitigates() && d.clientTicks < d.attacksBlockedUntilTicks;
		Optional<Flag> multi = MultiTargetCheck.onAttack(d.multiTarget, entityId);
		if (cfg.multiTargetEnabled() && multi.isPresent() && handle(player, multi.get(), cfg, cfg.multiTargetKickVl(), CANCEL_ATTACK)) {
			return true;
		}
		Entity target = player.level().getEntity(entityId);
		if (target == null || (!cfg.reachEnabled() && !cfg.hitboxEnabled()) || positionUnconfirmed(player, d)) {
			return blocked; // after a teleport, the server's eye is not where the client attacked from
		}
		TargetView view = targetView(player, target);
		if (view == null) {
			return blocked;
		}
		if (cfg.hitboxEnabled() && d.pendingAttacks.size() < 16) {
			d.pendingAttacks.add(new PendingAttack(view.eyes(), view.candidates(),
					new HitboxCheck.Rotation(player.getYRot(), player.getXRot()), view.maxReach() + 1.0, view.synced()));
		}
		if (cfg.reachEnabled()) {
			Optional<Flag> reach = ReachCheck.evaluate(view.eyes(), view.candidates(), view.maxReach(), cfg.reachTolerance(),
					view.synced(), sync.rttMs(player.getUUID()));
			if (reach.isPresent() && handle(player, reach.get(), cfg, cfg.reachKickVl(), CANCEL_ATTACK)) {
				return true;
			}
		}
		return blocked;
	}

	/** Where the attacker's eye and the target may have been on the attacker's screen. */
	record TargetView(List<Vec3> eyes, List<AABB> candidates, double maxReach, boolean synced) {
	}

	private TargetView targetView(ServerPlayer player, Entity target) {
		ItemStack weapon = player.getMainHandItem();
		if (weapon.has(DataComponents.PIERCING_WEAPON)) {
			return null; // vanilla ignores attack packets for spears; they use STAB instead
		}
		AttackRange range = player.getAttackRangeWith(weapon);
		double maxReach = range.effectiveMaxRange(player) + range.hitboxMargin();
		if (maxReach > EntityHistory.RADIUS - 1) {
			return null; // beyond what the history covers; not judged
		}
		UUID id = player.getUUID();
		int now = player.level().getServer().getTickCount();
		OptionalInt ack = sync.lastAckTick(id);
		boolean synced = ack.isPresent();
		int from = synced ? ReachCheck.windowStart(ack.getAsInt(), target.getType().updateInterval()) : now - ReachCheck.UNSYNCED_WINDOW;
		int to = synced ? ack.getAsInt() + 1 : now;
		List<AABB> boxes = new ArrayList<>(history.boxes(target.getId(), from, to));
		if (boxes.isEmpty()) {
			boxes.add(target.getBoundingBox());
		}
		double pick = target.getPickRadius();
		List<AABB> candidates = new ArrayList<>();
		for (AABB box : ReachCheck.sweep(boxes)) {
			candidates.add(pick > 0 ? box.inflate(pick) : box);
		}
		return new TargetView(eyes(player), candidates, maxReach, synced);
	}

	private static List<Vec3> eyes(ServerPlayer player) {
		Vec3 feet = player.position();
		return List.of(
				player.getEyePosition(),
				feet.add(0, player.getDimensions(Pose.STANDING).eyeHeight(), 0),
				feet.add(0, player.getDimensions(Pose.CROUCHING).eyeHeight(), 0));
	}

	private boolean evaluatePendingAttacks(ServerPlayer player, PlayerData d, AcConfig cfg) {
		if (d.pendingAttacks.isEmpty()) {
			return false;
		}
		List<PendingAttack> attacks = List.copyOf(d.pendingAttacks);
		d.pendingAttacks.clear();
		if (!cfg.hitboxEnabled()) {
			return false;
		}
		HitboxCheck.Rotation latest = new HitboxCheck.Rotation(d.yaw, d.pitch);
		for (PendingAttack attack : attacks) {
			Optional<Flag> flag = HitboxCheck.evaluate(attack.eyes(), List.of(attack.atAttack(), latest), attack.candidates(),
					attack.rayLength(), attack.synced(), sync.rttMs(player.getUUID()));
			if (flag.isPresent() && handle(player, flag.get(), cfg, cfg.hitboxKickVl(), blockAttacks(d))) {
				return true;
			}
		}
		return false;
	}

	/** The hit behind a hitbox flag was already processed: cancel the player's attacks for a moment instead. */
	private static Mitigator blockAttacks(PlayerData d) {
		return () -> {
			d.attacksBlockedUntilTicks = d.clientTicks + HITBOX_ATTACK_BLOCK_TICKS;
			return new Mitigated("block_attacks", false);
		};
	}

	/**
	 * Server thread: the client started breaking a block, or used/placed against one.
	 *
	 * @param face     the clicked face for {@code use_on_block}, null otherwise
	 * @param sequence the packet's block-change sequence number, acknowledged if the action is cancelled
	 * @return {@code true} if the action must not be processed: the player was kicked, or the action is
	 *         mitigated (cancelled)
	 */
	public boolean onBlockInteraction(ServerPlayer player, BlockPos pos, String action, @Nullable Direction face, int sequence) {
		long start = Metrics.Timer.start();
		try {
			return onBlockInteractionMeasured(player, pos, action, face, sequence);
		} finally {
			T_BLOCK_INTERACTION.stop(start);
		}
	}

	private boolean onBlockInteractionMeasured(ServerPlayer player, BlockPos pos, String action, @Nullable Direction face, int sequence) {
		AcConfig cfg = config;
		if (!cfg.enabled() || !cfg.blockReachEnabled() || player.isSpectator() || positionUnconfirmed(player, data(player.getUUID()))) {
			return false;
		}
		// Pure geometry on the client-chosen position: no block or chunk is looked up.
		Optional<Flag> flag = BlockReachCheck.evaluate(eyes(player), new AABB(pos), player.blockInteractionRange(),
				cfg.blockReachTolerance(), action);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.blockReachKickVl(), cancelBlockAction(player, pos, face, sequence));
	}

	/**
	 * Mitigation of a block action: the packet is not processed, and the client is told what vanilla
	 * would have told it after refusing the action (the sequence acknowledged, the real block states, the
	 * inventory after a refused use). Only for blocks within vanilla's own range: beyond it vanilla
	 * refuses the action itself, and a far block is never looked up.
	 */
	private Mitigator cancelBlockAction(ServerPlayer player, BlockPos pos, @Nullable Direction face, int sequence) {
		return () -> {
			if (!withinBlockRange(player, pos)) {
				return null;
			}
			player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
			if (face != null) {
				player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos.relative(face)));
				player.containerMenu.sendAllDataToRemote();
			}
			if (sequence >= 0) {
				// The acknowledgement vanilla sends after handling the action: the client drops its prediction.
				player.connection.send(new ClientboundBlockChangedAckPacket(sequence));
			}
			return new Mitigated("cancel_action", true);
		};
	}

	/**
	 * Any thread: the server sent this player a velocity change (knockback, explosion, wind charge...).
	 * Tagged with the next tick: a packet sent between ticks follows the current tick's ping, so only
	 * the next ping's pong proves the client applied it.
	 */
	public void onImpulseSent(ServerPlayer player) {
		PlayerData d = players.get(player.getUUID());
		if (d != null) {
			d.impulseTick = player.level().getServer().getTickCount() + 1;
			d.impulseNanos = System.nanoTime();
			d.impulsePending = true;
		}
	}

	/** Any thread: the server teleported or respawned this player. */
	public void onTeleportSent(ServerPlayer player) {
		PlayerData d = players.get(player.getUUID());
		if (d != null) {
			d.teleportTick = player.level().getServer().getTickCount();
			d.teleportNanos = System.nanoTime();
			d.teleportPending = true;
		}
	}

	/**
	 * Whether the client may still act from where it was before a teleport or respawn, while the
	 * server already has the new position: vanilla awaits the teleport confirmation, or tick sync has
	 * not yet shown a ping sent after the teleport answered (one second when unsynced). Reach, hitbox
	 * and block reach measure from the server's eye, so they are skipped until then.
	 */
	private boolean positionUnconfirmed(ServerPlayer player, PlayerData d) {
		if (((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null) {
			return true;
		}
		if (!d.teleportPending) {
			return false;
		}
		OptionalInt ack = sync.lastAckTick(player.getUUID());
		// "> tick": a teleport sent between ticks follows that tick's ping, so only the next ping proves it.
		boolean applied = ack.isPresent() ? ack.getAsInt() > d.teleportTick
				: System.nanoTime() - d.teleportNanos > UNSYNCED_TELEPORT_NANOS;
		if (applied) {
			d.teleportPending = false;
		}
		return !applied;
	}

	/**
	 * Any thread: the server corrected the player's vehicle (rejected move, teleport, push, explosion).
	 * Tagged with the next tick, like {@link #onImpulseSent}.
	 */
	public void onVehicleImpulseSent(ServerPlayer player) {
		PlayerData d = players.get(player.getUUID());
		if (d != null) {
			d.vehicleImpulseTick = player.level().getServer().getTickCount() + 1;
			d.vehicleImpulseNanos = System.nanoTime();
			d.vehicleImpulsePending = true;
		}
	}

	/** Like {@link #impulseUntrusted}, for corrections of the player's vehicle. */
	private boolean vehicleImpulseUntrusted(ServerPlayer player, PlayerData d) {
		if (!d.vehicleImpulsePending) {
			return false;
		}
		OptionalInt ack = sync.lastAckTick(player.getUUID());
		boolean applied = ack.isPresent() ? ack.getAsInt() >= d.vehicleImpulseTick
				: System.nanoTime() - d.vehicleImpulseNanos > 2_000_000_000L;
		if (applied) {
			d.vehicleImpulsePending = false;
		}
		return true;
	}

	/**
	 * Whether a velocity change from the server (knockback, explosion...) is still in flight or was
	 * just applied by the client; movement predictions must restart after it.
	 */
	private boolean impulseUntrusted(ServerPlayer player, PlayerData d) {
		if (!d.impulsePending) {
			return false;
		}
		OptionalInt ack = sync.lastAckTick(player.getUUID());
		boolean applied = ack.isPresent() ? ack.getAsInt() >= d.impulseTick : System.nanoTime() - d.impulseNanos > 2_000_000_000L;
		if (applied) {
			d.impulsePending = false;
		}
		return true; // this tick is the first one with the new velocity (or still before it)
	}

	private boolean evaluateFly(ServerPlayer player, PlayerData d, AcConfig cfg, boolean impulse, double dy) {
		boolean untrusted = impulse || d.ticksSincePassenger < PASSENGER_GRACE_TICKS || flyUntrusted(player, d, dy);
		Optional<Flag> flag = FlyCheck.onClientTick(d.fly, dy, player.getGravity(), untrusted);
		return flag.isPresent() && handleMovement(player, d, flag.get(), cfg, cfg.flyKickVl());
	}

	/** Whether this tick's vertical movement cannot be predicted from the previous one. */
	private boolean flyUntrusted(ServerPlayer player, PlayerData d, double dy) {
		// A client that believes it stands on something (possibly a block it has not yet seen removed)
		// legitimately does not fall; lying about it is movement.ground_spoof's job.
		if (d.claimedOnGround || groundExempt(player) || player.getAbilities().mayfly && player.getAbilities().flying
				|| player.hasEffect(MobEffects.SLOW_FALLING) || player.isInPowderSnow || touchesSlowingBlock(player)) {
			return true;
		}
		// Ground, or a collision on the predicted path (landing), limits the movement: not predictable.
		AABB box = player.getBoundingBox();
		if (!player.level().noCollision(player, box.expandTowards(0, -0.05, 0))) {
			return true;
		}
		OptionalDouble expected = FlyCheck.expectedDy(d.fly, player.getGravity());
		if (expected.isPresent() && expected.getAsDouble() < 0) {
			AABB before = box.move(0, -dy, 0);
			return !player.level().noCollision(player, before.expandTowards(0, expected.getAsDouble() - 0.05, 0));
		}
		return false;
	}

	private boolean evaluateSpeed(ServerPlayer player, PlayerData d, AcConfig cfg, boolean impulse) {
		double x = player.getX();
		double z = player.getZ();
		double moved = Double.isNaN(d.lastTickX) ? 0 : Math.hypot(x - d.lastTickX, z - d.lastTickZ);
		d.lastTickX = x;
		d.lastTickZ = z;
		boolean groundNow = !player.level().noCollision(player, player.getBoundingBox().expandTowards(0, -0.05, 0));
		float[] friction = frictionUnder(player);
		// Vanilla order within a tick: acceleration uses the ground state at the start of this tick
		// (end of the last one); the decay carried over from the last tick used the ground state at the
		// start of that tick (two ticks ago). Take the most permissive of everything that could apply.
		boolean groundBefore = d.groundOneAgo;
		double decay = d.groundTwoAgo && d.groundOneAgo
				? Math.max(friction[1], Math.max(d.frictionOneAgo, d.frictionTwoAgo)) * 0.91 : 0.91;
		double moveSpeed = player.getAttributeValue(Attributes.MOVEMENT_SPEED) * (player.isSprinting() ? 1.0 : 1.3);
		double groundAccel = groundBefore || groundNow ? moveSpeed * 0.216 / Math.pow(friction[0], 3) : 0;
		double accel = Math.max(groundAccel, 0.026);
		// Under a low ceiling the jump barely leaves the ground, but its sprint boost still applies.
		boolean ceiling = groundBefore && groundNow && player.isSprinting()
				&& !player.level().noCollision(player, player.getBoundingBox().expandTowards(0, SpeedCheck.CEILING_REACH, 0));
		boolean tookOff = SpeedCheck.sprintJumpPossible(groundBefore, groundNow, player.isSprinting(), ceiling);
		d.groundTwoAgo = d.groundOneAgo;
		d.groundOneAgo = groundNow;
		d.frictionTwoAgo = d.frictionOneAgo;
		d.frictionOneAgo = friction[1];
		boolean untrusted = impulse || d.ticksSincePassenger < PASSENGER_GRACE_TICKS || d.ticksSinceGliding < GLIDE_GRACE_TICKS
				|| groundExempt(player);
		Optional<Flag> flag = SpeedCheck.onClientTick(d.speed, moved, decay, accel, tookOff, untrusted);
		return flag.isPresent() && handleMovement(player, d, flag.get(), cfg, cfg.speedKickVl());
	}

	/**
	 * Judges take-offs: the first airborne tick after ground. See {@link JumpCheck} for what is
	 * allowed and what is not judged.
	 */
	private boolean evaluateJump(ServerPlayer player, PlayerData d, AcConfig cfg, boolean impulse, double dy) {
		boolean groundNow = touchesGround(player);
		boolean groundBefore = d.jumpGroundBefore;
		double fall = 0;
		for (double previous : d.recentDy) {
			fall = Math.max(fall, -previous);
		}
		double bounce = d.bouncyBelow ? fall : 0;
		double power = jumpPowerAllowance(player, d);

		d.recentDy[(int) (d.clientTicks & 3)] = dy;
		d.jumpGroundBefore = groundNow || d.claimedOnGround;
		if (groundNow) {
			d.bouncyBelow = bouncyUnder(player);
		}
		if (!groundBefore || groundNow || d.claimedOnGround || dy <= 0 || impulse || jumpUntrusted(player, d)) {
			return false;
		}
		Optional<Flag> flag = JumpCheck.onTakeOff(d.jump, dy, power, bounce, d.clientTicks);
		return flag.isPresent() && handleMovement(player, d, flag.get(), cfg, cfg.jumpKickVl());
	}

	/**
	 * Jump strength plus Jump Boost, kept at its highest value for a while after it drops: the client
	 * still has an effect for a round trip after the server removed it.
	 */
	private double jumpPowerAllowance(ServerPlayer player, PlayerData d) {
		double current = player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
		long now = System.nanoTime();
		if (current >= d.jumpPowerMax || now > d.jumpPowerMaxUntilNanos) {
			int rtt = sync.recentMaxRttMs(player.getUUID());
			long keepMs = JUMP_POWER_GRACE_MS + 2L * (rtt < 0 ? 1_000 : rtt);
			d.jumpPowerMax = current;
			d.jumpPowerMaxUntilNanos = now + keepMs * 1_000_000L;
		}
		return d.jumpPowerMax;
	}

	/** Situations in which a take-off may legitimately be higher than a jump; never judged. */
	private boolean jumpUntrusted(ServerPlayer player, PlayerData d) {
		if (d.ticksSincePassenger < PASSENGER_GRACE_TICKS || groundExempt(player)
				|| player.getAbilities().mayfly && player.getAbilities().flying || player.isInPowderSnow
				|| touchesSlowingBlock(player)) {
			return true;
		}
		AABB around = player.getBoundingBox().inflate(1.5);
		return nearPiston(player, around)
				|| !player.level().getEntities(player, player.getBoundingBox().inflate(1.0), e -> e.canBeCollidedWith(player)).isEmpty();
	}

	private static boolean touchesGround(Entity entity) {
		return !entity.level().noCollision(entity, entity.getBoundingBox().expandTowards(0, -0.05, 0));
	}

	/** Slime blocks and beds bounce players (and mounts) that land on them. */
	private static boolean bouncyUnder(Entity player) {
		AABB box = player.getBoundingBox();
		double y = box.minY - 0.2;
		for (double[] corner : new double[][] {{box.minX, box.minZ}, {box.minX, box.maxZ}, {box.maxX, box.minZ}, {box.maxX, box.maxZ}}) {
			BlockState state = player.level().getBlockState(BlockPos.containing(corner[0], y, corner[1]));
			if (state.getBlock() instanceof SlimeBlock || state.getBlock() instanceof BedBlock) {
				return true;
			}
		}
		return false;
	}

	/** Moving pistons push entities, including upwards. */
	private static boolean nearPiston(Entity entity, AABB box) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
				BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = entity.level().getBlockState(pos);
			if (state.is(Blocks.MOVING_PISTON) || state.is(Blocks.PISTON_HEAD)) {
				return true;
			}
		}
		return false;
	}

	/** A movement flag: the position is not a safe setback target, and mitigation sets the player back. */
	private boolean handleMovement(ServerPlayer player, PlayerData d, Flag flag, AcConfig cfg, double kickVl) {
		d.movementFlagTicks = d.clientTicks;
		return handle(player, flag, cfg, kickVl, setback(player, d));
	}

	/**
	 * Mitigation of a movement flag: vanilla's own position correction (the teleport it sends a player
	 * who "moved wrongly") back to the newest safe position. Never into a block, never across levels or
	 * farther than {@link Setbacks#MAX_DISTANCE}, at most once per {@link Setbacks#MIN_INTERVAL_NANOS},
	 * and not while a teleport is still unconfirmed.
	 */
	private Mitigator setback(ServerPlayer player, PlayerData d) {
		return () -> {
			Vec3 target = d.safeLevel == player.level() ? d.safePos : null;
			long now = System.nanoTime();
			boolean unconfirmed = ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null;
			double distance = target == null ? Double.NaN : target.distanceTo(player.position());
			if (player.isPassenger() || !Setbacks.allowed(target != null, distance, d.lastSetbackNanos, now, unconfirmed)) {
				return null;
			}
			Vec3 offset = target.subtract(player.position());
			if (!player.level().noCollision(player, player.getBoundingBox().move(offset.x, offset.y, offset.z))) {
				return null; // a block was placed there since: never set a player back into a block
			}
			d.lastSetbackNanos = now;
			d.setbackTicks = d.clientTicks;
			player.connection.teleport(target.x, target.y, target.z, player.getYRot(), player.getXRot());
			return new Mitigated("setback", false);
		};
	}

	/**
	 * Remembers this client tick's position as the setback target if it is safe (see {@link Setbacks}).
	 * Only while mitigation is on; otherwise nothing is kept.
	 */
	private void updateSafePosition(ServerPlayer player, PlayerData d, AcConfig cfg) {
		if (!cfg.mitigates()) {
			d.safePos = null;
			d.safeLevel = null;
			return;
		}
		boolean violating = cfg.flyEnabled() && d.fly.violating() || cfg.speedEnabled() && d.speed.violating()
				|| cfg.elytraEnabled() && d.elytra.violating();
		boolean unreliable = player.isPassenger() || player.isRemoved() || player.isDeadOrDying() || player.isSleeping()
				|| player.isChangingDimension() || d.setbackTicks == d.clientTicks
				|| ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null;
		if (Setbacks.isSafeTick(violating, d.movementFlagTicks == d.clientTicks, unreliable)) {
			d.safePos = player.position();
			d.safeLevel = player.level();
		}
	}

	/** Judges elytra flight against glide physics. See {@link ElytraCheck}. */
	private boolean evaluateElytra(ServerPlayer player, PlayerData d, AcConfig cfg, boolean impulse, boolean moved) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean known = !Double.isNaN(d.glideLastX);
		ElytraCheck.Motion motion = known ? new ElytraCheck.Motion(x - d.glideLastX, y - d.glideLastY, z - d.glideLastZ)
				: new ElytraCheck.Motion(0, 0, 0);
		d.glideLastX = x;
		d.glideLastY = y;
		d.glideLastZ = z;
		if (!player.isFallFlying()) {
			d.elytra.reset();
			return false;
		}
		String reset = !known || !moved ? "no_position" : impulse ? "server_velocity" : elytraUntrusted(player, d, motion);
		float yaw = player.getYRot();
		float pitch = Math.max(-90.0F, Math.min(90.0F, player.getXRot()));
		if (traceMovement) {
			log.info("[trace] elytra t={} move=({}, {}, {}) yaw={} pitch={} violating={} reset={}", d.clientTicks, motion.x(),
					motion.y(), motion.z(), yaw, pitch, d.elytra.violating(), reset);
		}
		Optional<Flag> flag = ElytraCheck.onClientTick(d.elytra, motion, yaw, pitch, player.getGravity(), cfg.elytraTolerance(),
				reset != null);
		return flag.isPresent() && handleMovement(player, d, flag.get(), cfg, cfg.elytraKickVl());
	}

	/** Why this tick of elytra flight cannot be predicted from the previous one, or null if it can. */
	private @Nullable String elytraUntrusted(ServerPlayer player, PlayerData d, ElytraCheck.Motion motion) {
		if (d.claimedOnGround) {
			return "ground_claim";
		}
		if (player.isRemoved() || player.isPassenger() || player.isSpectator() || player.isSleeping() || player.isDeadOrDying()
				|| player.getAbilities().flying || player.isAutoSpinAttack() || player.onClimbable() || player.isInWater()
				|| player.isInLava() || player.hasEffect(MobEffects.LEVITATION) || player.hasEffect(MobEffects.SLOW_FALLING)
				|| player.isChangingDimension()
				|| ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null) {
			return "state";
		}
		if (d.ticksSincePassenger < PASSENGER_GRACE_TICKS) {
			return "vehicle";
		}
		if (rocketBoostPossible(player, d)) {
			return "firework";
		}
		AABB box = player.getBoundingBox();
		// Any block on or next to this tick's path: landing, walls, step-ups.
		AABB path = box.minmax(box.move(-motion.x(), -motion.y(), -motion.z())).inflate(0.5);
		if (!player.level().noCollision(player, path) || player.level().containsAnyLiquid(path)) {
			return "blocks";
		}
		if (touchesSlowingBlock(player) || nearPiston(player, box.inflate(1.5))) {
			return "block";
		}
		boolean entities = !player.level().getEntities(player, box.inflate(1.0),
				e -> e.canBeCollidedWith(player) || e.isPushable()).isEmpty();
		return entities ? "entity" : null;
	}

	/**
	 * Whether a firework rocket may be boosting the client: one was next to the player at the end of a server
	 * tick the client has not yet confirmed past. The client removes its copy of the rocket only when the
	 * server's removal arrives, one network delay after the server's.
	 */
	private boolean rocketBoostPossible(ServerPlayer player, PlayerData d) {
		if (d.rocketTick == Integer.MIN_VALUE) {
			return false;
		}
		OptionalInt ack = sync.lastAckTick(player.getUUID());
		return ack.isPresent() ? ack.getAsInt() <= d.rocketTick + 1 : System.nanoTime() - d.rocketNanos < UNSYNCED_WINDOW_NANOS;
	}

	/** Judges the vertical movement of a boat or raft the player steers. See {@link BoatFlyCheck}. */
	private boolean evaluateBoatFly(ServerPlayer player, PlayerData d, AcConfig cfg) {
		if (!(player.getVehicle() instanceof AbstractBoat boat) || boat.getControllingPassenger() != player) {
			if (d.vehicleId != -1) {
				d.vehicleId = -1;
				d.lastVehicleY = Double.NaN;
				d.boatFly.reset();
			}
			return false;
		}
		if (boat.getId() != d.vehicleId) {
			d.vehicleId = boat.getId();
			d.vehicleTicks = 0;
			d.lastVehicleY = Double.NaN;
		}
		d.vehicleTicks++;
		double y = boat.getY();
		double dy = Double.isNaN(d.lastVehicleY) ? 0 : y - d.lastVehicleY;
		d.lastVehicleY = y;
		String reset = vehicleImpulseUntrusted(player, d) ? "correction"
				: d.vehicleTicks <= MOUNT_GRACE_TICKS ? "mounting" : boatUntrusted(player, boat, d, dy);
		if (traceMovement) {
			log.info("[trace] boat t={} y={} dy={} expected={} reset={}", d.clientTicks, y, dy,
					BoatFlyCheck.expectedDy(d.boatFly, boat.getGravity()), reset);
		}
		Optional<Flag> flag = BoatFlyCheck.onClientTick(d.boatFly, dy, boat.getGravity(), reset != null);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.boatFlyKickVl());
	}

	/** Why this tick's vertical boat movement cannot be predicted from the previous one, or null if it can. */
	private String boatUntrusted(ServerPlayer player, AbstractBoat boat, PlayerData d, double dy) {
		if (player.isChangingDimension() || boat.isLeashed()
				|| ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null) {
			return "teleport_or_leash";
		}
		AABB box = boat.getBoundingBox();
		// Water, lava and bubble columns in or just below the boat: buoyancy, surface snapping, launches.
		if (boat.level().containsAnyLiquid(box.inflate(0.1).expandTowards(0, -1.0, 0))) {
			return "liquid";
		}
		// Ground, or a collision on the predicted path (landing, slime bounces): not predictable.
		if (touchesGround(boat)) {
			return "ground";
		}
		OptionalDouble expected = BoatFlyCheck.expectedDy(d.boatFly, boat.getGravity());
		if (expected.isPresent() && expected.getAsDouble() < 0) {
			AABB before = box.move(0, -dy, 0);
			if (!boat.level().noCollision(boat, before.expandTowards(0, expected.getAsDouble() - 0.05, 0))) {
				return "landing";
			}
		}
		if (touchesSlowingBlock(boat) || nearPiston(boat, box.inflate(1.5))) {
			return "block";
		}
		boolean entities = !boat.level().getEntities(boat, box.inflate(0.5),
				e -> !boat.hasPassenger(e) && e.canBeCollidedWith(boat)).isEmpty();
		return entities ? "entity" : null;
	}

	/** Judges a horse-like mount the player rides and steers. See {@link HorseCheck}. */
	private boolean evaluateHorse(ServerPlayer player, PlayerData d, AcConfig cfg) {
		if (!(player.getVehicle() instanceof AbstractHorse horse) || horse.getControllingPassenger() != player) {
			if (d.horseId != -1) {
				d.horseId = -1;
				d.horseLastX = Double.NaN;
				d.horse.reset();
			}
			return false;
		}
		if (horse.getId() != d.horseId) {
			d.horseId = horse.getId();
			d.horseTicks = 0;
			d.horseLastX = Double.NaN;
			d.horseGroundOneAgo = true;
			d.horseGroundTwoAgo = true;
			d.horseBouncyBelow = false;
			d.horse.reset();
		}
		d.horseTicks++;
		double x = horse.getX();
		double y = horse.getY();
		double z = horse.getZ();
		boolean known = !Double.isNaN(d.horseLastX);
		double dy = known ? y - d.horseLastY : 0;
		double speed = known ? Math.hypot(x - d.horseLastX, z - d.horseLastZ) : 0;
		d.horseLastX = x;
		d.horseLastY = y;
		d.horseLastZ = z;
		long now = System.nanoTime();
		int serverTick = player.level().getServer().getTickCount();

		// Ground: the server's own collision test, or the client's claim while the server just saw support
		// there (a block removed under the mount reaches the client one network delay later).
		boolean serverGround = touchesGround(horse);
		if (serverGround) {
			d.horseSupportTick = serverTick;
			d.horseSupportNanos = now;
		}
		boolean groundNow = serverGround || horse.onGround() && supportMaybeVisible(player, d, now);
		boolean groundBefore = d.horseGroundOneAgo;
		float[] friction = frictionUnder(horse);
		double decay = d.horseGroundTwoAgo && d.horseGroundOneAgo
				? Math.max(friction[1], Math.max(d.horseFrictionOneAgo, d.horseFrictionTwoAgo)) * 0.91 : 0.91;
		d.horseGroundTwoAgo = d.horseGroundOneAgo;
		d.horseGroundOneAgo = groundNow;
		d.horseFrictionTwoAgo = d.horseFrictionOneAgo;
		d.horseFrictionOneAgo = friction[1];
		boolean bouncy = d.horseBouncyBelow;
		if (groundNow) {
			d.horseBouncyBelow = bouncyUnder(horse);
		}

		// Allowances: the mount's own attributes, at their highest of the recent past (effects end on the client later).
		boolean camel = EntityType.getKey(horse.getType()).getPath().contains("camel");
		int rtt = sync.recentMaxRttMs(player.getUUID());
		long keepNanos = (JUMP_POWER_GRACE_MS + 2L * (rtt < 0 ? 1_000 : rtt)) * 1_000_000L;
		double movementSpeed = d.horse.speed.update(horse.getAttributeValue(Attributes.MOVEMENT_SPEED), now, keepNanos);
		double jumpPower = d.horse.jump.update(horse.getAttributeValue(Attributes.JUMP_STRENGTH) + horse.getJumpBoostPower(),
				now, keepNanos);
		double ridden = HorseCheck.riddenSpeed(movementSpeed, camel);
		double accel = HorseCheck.acceleration(ridden, groundBefore || groundNow, friction[0]);
		AABB box = horse.getBoundingBox();
		boolean ceiling = groundBefore && groundNow
				&& !horse.level().noCollision(horse, box.expandTowards(0, SpeedCheck.CEILING_REACH + 0.3, 0));
		double boost = SpeedCheck.sprintJumpPossible(groundBefore, groundNow, true, ceiling) ? HorseCheck.jumpBoost(movementSpeed, camel) : 0;
		OptionalDouble expected = HorseCheck.expectedDy(d.horse, horse.getGravity());
		boolean pathBlocked = expected.isPresent() && expected.getAsDouble() < 0
				&& !horse.level().noCollision(horse, box.move(0, -dy, 0).expandTowards(0, expected.getAsDouble() - 0.05, 0));

		String reset = !known ? "no_position" : vehicleImpulseUntrusted(player, d) ? "correction"
				: d.horseTicks <= HORSE_MOUNT_GRACE_TICKS ? "mounting" : bouncy ? "bouncy" : horseUntrusted(player, horse);
		HorseCheck.Tick tick = new HorseCheck.Tick(dy, speed, groundBefore, groundNow, pathBlocked,
				HorseCheck.maxTakeOff(jumpPower, 0, camel), horse.getGravity(), decay, accel, boost);
		if (traceMovement) {
			log.info("[trace] horse t={} y={} dy={} speed={} ground={}/{} expected={} reset={}", d.clientTicks, y, dy, speed,
					groundBefore, groundNow, expected, reset);
		}
		Optional<Flag> flag = HorseCheck.onClientTick(d.horse, tick, cfg.horseSpeedTolerance(), reset != null);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.horseKickVl());
	}

	/** Whether the client may not yet know that the support the server last saw under its mount is gone. */
	private boolean supportMaybeVisible(ServerPlayer player, PlayerData d, long now) {
		if (d.horseSupportTick == Integer.MIN_VALUE) {
			return false;
		}
		OptionalInt ack = sync.lastAckTick(player.getUUID());
		return ack.isPresent() ? ack.getAsInt() <= d.horseSupportTick + 1 : now - d.horseSupportNanos < UNSYNCED_WINDOW_NANOS;
	}

	/** Why this tick of the mount cannot be predicted at all, or null if it can. */
	private @Nullable String horseUntrusted(ServerPlayer player, AbstractHorse horse) {
		if (player.isChangingDimension() || horse.isLeashed()
				|| ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null) {
			return "teleport_or_leash";
		}
		AABB box = horse.getBoundingBox();
		if (horse.level().containsAnyLiquid(box.inflate(0.1).expandTowards(0, -1.0, 0))) {
			return "liquid";
		}
		if (horse.hasEffect(MobEffects.LEVITATION) || horse.hasEffect(MobEffects.SLOW_FALLING)) {
			return "effect";
		}
		if (touchesSlowingBlock(horse) || nearPiston(horse, box.inflate(1.5)) || bouncyUnder(horse)) {
			return "block";
		}
		boolean entities = !horse.level().getEntities(horse, box.inflate(0.5),
				e -> !horse.hasPassenger(e) && (e.canBeCollidedWith(horse) || e.isPushable())).isEmpty();
		return entities ? "entity" : null;
	}

	/** {min, max} friction of the blocks under the player's feet (0.6 for most, ice up to 0.989). */
	private static float[] frictionUnder(Entity player) {
		AABB box = player.getBoundingBox();
		double y = box.minY - 0.5000001;
		float min = Float.MAX_VALUE;
		float max = 0;
		for (double[] corner : new double[][] {{box.minX, box.minZ}, {box.minX, box.maxZ}, {box.maxX, box.minZ}, {box.maxX, box.maxZ}}) {
			float f = player.level().getBlockState(BlockPos.containing(corner[0], y, corner[1])).getBlock().getFriction();
			min = Math.min(min, f);
			max = Math.max(max, f);
		}
		return new float[] {min, max};
	}

	/**
	 * Server thread: the client started, finished or aborted breaking a block.
	 *
	 * @param sequence the packet's block-change sequence number, acknowledged if the action is cancelled
	 * @return {@code true} if the action must not be processed: the player was kicked, or the action is
	 *         mitigated (cancelled)
	 */
	public boolean onPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket.Action action, BlockPos pos, int sequence) {
		long start = Metrics.Timer.start();
		try {
			return onPlayerActionMeasured(player, action, pos, sequence);
		} finally {
			T_PLAYER_ACTION.stop(start);
		}
	}

	private boolean onPlayerActionMeasured(ServerPlayer player, ServerboundPlayerActionPacket.Action action, BlockPos pos, int sequence) {
		AcConfig cfg = config;
		if (!cfg.enabled() || player.isSpectator()) {
			return false;
		}
		PlayerData d = data(player.getUUID());
		switch (action) {
			case START_DESTROY_BLOCK -> {
				if (onBlockInteraction(player, pos, "start_destroy", null, sequence)) {
					return true;
				}
				if (cfg.fastBreakEnabled() && !player.isCreative() && withinBlockRange(player, pos)) {
					d.breakPos = pos.immutable();
					d.breakStartTicks = d.clientTicks;
					d.breakProgress = bestDestroyProgress(player, pos);
				} else {
					d.breakPos = null;
				}
			}
			case STOP_DESTROY_BLOCK -> {
				if (cfg.fastBreakEnabled() && pos.equals(d.breakPos)) {
					if (!withinBlockRange(player, pos)) {
						d.breakPos = null; // vanilla refuses it too; never look up a far block
						return false;
					}
					double progress = Math.max(d.breakProgress, bestDestroyProgress(player, pos));
					int ticks = (int) Math.min(Integer.MAX_VALUE, d.clientTicks - d.breakStartTicks);
					d.breakPos = null;
					Optional<Flag> flag = FastBreakCheck.onFinish(progress, ticks);
					boolean cancelled = flag.isPresent() && handle(player, flag.get(), cfg, cfg.fastBreakKickVl(),
							cancelBlockAction(player, pos, null, sequence));
					if (cancelled) {
						d.breakPos = pos.immutable(); // vanilla is still breaking it: a repeated early finish is judged again
					}
					return cancelled;
				}
			}
			case ABORT_DESTROY_BLOCK -> d.breakPos = null;
			default -> {
			}
		}
		return false;
	}

	/**
	 * Whether a block position chosen by the client is within the player's block interaction range
	 * plus vanilla's own one-block margin. This runs before vanilla's range check, so a block state is
	 * only ever looked up next to the player, where chunks are loaded: reading one far away would load
	 * or generate chunks on the server thread.
	 */
	private static boolean withinBlockRange(ServerPlayer player, BlockPos pos) {
		double range = player.blockInteractionRange() + BLOCK_RANGE_MARGIN;
		AABB block = new AABB(pos);
		for (Vec3 eye : eyes(player)) {
			if (block.distanceToSqr(eye) <= range * range) {
				return true;
			}
		}
		return false;
	}

	/** Destroy progress per tick in the player's favour: without the airborne and underwater penalties. */
	private static double bestDestroyProgress(ServerPlayer player, BlockPos pos) {
		BlockState state = player.level().getBlockState(pos);
		if (state.isAir()) {
			return 0;
		}
		double progress = state.getDestroyProgress(player, player.level(), pos);
		if (!player.onGround()) {
			progress *= 5.0;
		}
		if (player.isEyeInFluid(FluidTags.WATER)) {
			double submerged = player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
			if (submerged > 0 && submerged < 1) {
				progress /= submerged;
			}
		}
		return progress;
	}

	/** Cobwebs, berry bushes, honey, scaffolding and bubble columns change vertical movement. */
	private static boolean touchesSlowingBlock(Entity entity) {
		AABB box = entity.getBoundingBox().inflate(0.1);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
				BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = entity.level().getBlockState(pos);
			if (state.is(Blocks.COBWEB) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.HONEY_BLOCK)
					|| state.is(Blocks.SCAFFOLDING) || state.is(Blocks.BUBBLE_COLUMN) || state.is(Blocks.POWDER_SNOW)) {
				return true;
			}
		}
		return false;
	}

	private static boolean groundExempt(ServerPlayer player) {
		// Removed: e.g. watching the End credits, while the client still ticks in the old level.
		return player.isRemoved() || player.isPassenger() || player.isSpectator() || player.isSleeping() || player.isDeadOrDying()
				|| player.getAbilities().flying || player.isFallFlying() || player.isAutoSpinAttack()
				|| player.onClimbable() || player.isInWater() || player.isInLava()
				|| player.hasEffect(MobEffects.LEVITATION) || player.isChangingDimension()
				|| ((ServerGamePacketListenerImplAccessor) player.connection).mejtaac$awaitingPositionFromClient() != null;
	}

	private static boolean hasGroundBelow(ServerPlayer player) {
		AABB below = player.getBoundingBox().expandTowards(0, -0.5, 0).inflate(0.1, 0, 0.1);
		return !player.level().noCollision(player, below);
	}

	// ---------------------------------------------------------------- pipeline

	private boolean handle(ServerPlayer player, Flag flag, AcConfig cfg, double kickThreshold) {
		return handle(player, flag, cfg, kickThreshold, null);
	}

	/**
	 * Assesses, decides, reports and acts on one flag.
	 *
	 * @param mitigator undoes the flagged action if mitigation applies (see {@link EnforcementPolicy#mitigates}),
	 *                  or null if this check has no mitigation
	 * @return {@code true} if the packet must not be processed further: the player was kicked, or the
	 *         mitigation cancels it
	 */
	private boolean handle(ServerPlayer player, Flag flag, AcConfig cfg, double kickThreshold, @Nullable Mitigator mitigator) {
		try {
			String name = player.getGameProfile().name();
			if (cfg.bypassPermission() && MejtaCoreAPI.hasPermission(player, AcPermissions.BYPASS)) {
				if (cfg.logs(LogLevel.DEBUG)) {
					log.info("[debug] {} exempt (bypass permission): {} {}", name, flag.checkId(), flag.reason());
				}
				return false;
			}

			long now = System.currentTimeMillis();
			UUID id = player.getUUID();
			// The total VL is reported; only the VL of confident flags counts towards a kick.
			boolean enforceable = flag.confidence().atLeast(cfg.enforcementMinConfidence());
			ViolationTracker.Levels levels = violations.add(id, flag.checkId(), flag.vlIncrease(), enforceable,
					cfg.decayPerMinute(), now);
			double vl = levels.total();
			Action action = EnforcementPolicy.decide(cfg.enforcementMode(), cfg.enforcementMinConfidence(), flag,
					levels.enforceable(), kickThreshold);
			// Undo the flagged action first, so the report says whether it was.
			Mitigated mitigated = mitigator != null && cfg.enabled() && EnforcementPolicy.mitigates(cfg.enforcementMode(),
					cfg.mitigation(), cfg.enforcementMinConfidence(), flag, action) ? mitigator.apply() : null;

			int suppressed = recordThrottle.tryPass(id, flag.checkId(), RECORD_INTERVAL_MS, now);
			boolean report = suppressed >= 0 || action != Action.NONE || cfg.logs(LogLevel.DEBUG);
			Detection detection = new Detection(id, name, player.level().dimension().identifier().toString(),
					player.connection.latency(), flag, vl, action, Math.max(suppressed, 0), mitigated == null ? "" : mitigated.action());

			for (Consumer<Detection> listener : flagListeners) {
				listener.accept(detection);
			}
			if (report) {
				logFlag(detection, cfg);
				if (cfg.recordsEnabled()) {
					records.violation(detection);
				}
			}
			if (cfg.alertsEnabled() && vl >= cfg.alertsMinVl()
					&& alertThrottle.tryPass(id, flag.checkId(), cfg.alertsCooldownSeconds() * 1000L, now) >= 0) {
				alerts.alert(detection);
			}
			if (action != Action.NONE && enforce(player, detection, cfg)) {
				return true;
			}
			return mitigated != null && mitigated.cancel();
		} catch (RuntimeException e) {
			log.error("Anti-cheat pipeline failed for {} ({}); packet handled normally", player.getGameProfile().name(), flag, e);
			return false;
		}
	}

	private boolean enforce(ServerPlayer player, Detection d, AcConfig cfg) {
		log.warn("{} {} for {} (VL {}, mode {})", d.playerName(), d.action() == Action.KICK ? "kicked" : "would be kicked",
				d.flag().checkId(), String.format(java.util.Locale.ROOT, "%.2f", d.violationLevel()), cfg.enforcementMode());
		if (cfg.recordsEnabled()) {
			records.enforcement(d, cfg.enforcementMode().name());
		}
		if (cfg.alertsEnforcement()) {
			alerts.enforcement(d, cfg.enforcementMode().name(), cfg.kickMessage());
		}
		// Reset so a dry run reports again only after the level builds up anew, not on every packet.
		violations.reset(d.playerId(), d.flag().checkId());
		if (d.action() == Action.KICK) {
			player.connection.disconnect(Component.literal(cfg.kickMessage()));
			return true;
		}
		return false;
	}

	private void logFlag(Detection d, AcConfig cfg) {
		if (!cfg.logs(LogLevel.NORMAL)) {
			return;
		}
		String vl = String.format(java.util.Locale.ROOT, "%.2f", d.violationLevel());
		String more = (d.suppressed() > 0 ? " (+" + d.suppressed() + " similar)" : "")
				+ (d.mitigated() ? " mitigated: " + d.mitigation() : "");
		if (cfg.logs(LogLevel.VERBOSE)) {
			log.info("{} failed {} [{}] VL {} conf {} {} ping {}ms world {}{}", d.playerName(), d.flag().checkId(), d.flag().reason(),
					vl, d.flag().confidence(), d.flag().measurements(), d.pingMs(), d.world(), more);
		} else {
			log.info("{} failed {} [{}] VL {}{}", d.playerName(), d.flag().checkId(), d.flag().reason(), vl, more);
		}
	}

	private void onConfigChanged(AcConfig updated) {
		AcConfig previous = config;
		config = updated;
		clearIfDisabled(previous.invalidMoveEnabled(), updated.invalidMoveEnabled(), InvalidMoveCheck.ID);
		clearIfDisabled(previous.timerEnabled(), updated.timerEnabled(), TimerCheck.ID);
		clearIfDisabled(previous.groundSpoofEnabled(), updated.groundSpoofEnabled(), GroundSpoofCheck.ID);
		clearIfDisabled(previous.reachEnabled(), updated.reachEnabled(), ReachCheck.ID);
		clearIfDisabled(previous.multiTargetEnabled(), updated.multiTargetEnabled(), MultiTargetCheck.ID);
		clearIfDisabled(previous.flyEnabled(), updated.flyEnabled(), FlyCheck.ID);
		clearIfDisabled(previous.hitboxEnabled(), updated.hitboxEnabled(), HitboxCheck.ID);
		clearIfDisabled(previous.blockReachEnabled(), updated.blockReachEnabled(), BlockReachCheck.ID);
		clearIfDisabled(previous.speedEnabled(), updated.speedEnabled(), SpeedCheck.ID);
		clearIfDisabled(previous.fastBreakEnabled(), updated.fastBreakEnabled(), FastBreakCheck.ID);
		clearIfDisabled(previous.jumpEnabled(), updated.jumpEnabled(), JumpCheck.ID);
		clearIfDisabled(previous.boatFlyEnabled(), updated.boatFlyEnabled(), BoatFlyCheck.ID);
		clearIfDisabled(previous.elytraEnabled(), updated.elytraEnabled(), ElytraCheck.ID);
		clearIfDisabled(previous.horseEnabled(), updated.horseEnabled(), HorseCheck.ID);
		if (!updated.elytraEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.elytra.reset();
				d.glideLastX = Double.NaN;
			});
		}
		if (!updated.horseEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.horse.reset();
				d.horseLastX = Double.NaN;
			});
		}
		if (!updated.mitigates()) {
			players.values().forEach(d -> {
				d.safePos = null;
				d.safeLevel = null;
				d.attacksBlockedUntilTicks = 0;
			});
		}
		if (!updated.boatFlyEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.boatFly.reset();
				d.lastVehicleY = Double.NaN;
			});
		}
		if (!updated.timerEnabled() || !updated.enabled()) {
			players.values().forEach(d -> d.timer.reset());
		}
		if (!updated.flyEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.fly.reset();
				d.lastTickY = Double.NaN;
			});
		}
		if (!updated.speedEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.speed.reset();
				d.lastTickX = Double.NaN;
				d.lastTickZ = Double.NaN;
			});
		}
		if (!previous.equals(updated)) {
			log.info("Configuration updated (enabled={}, enforcement={}, mitigation={}, log={})", updated.enabled(),
					updated.enforcementMode(), updated.mitigation(), updated.logLevel());
		}
	}

	private void clearIfDisabled(boolean before, boolean after, String checkId) {
		if (before && !after) {
			violations.clearCheck(checkId);
		}
	}

	/** Current violation levels of a player, for commands. */
	public Map<String, Double> levels(UUID player) {
		return violations.snapshot(player, config.decayPerMinute(), System.currentTimeMillis());
	}
}
