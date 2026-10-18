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

import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.block.Blocks;
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
import com.mejta.mejtaac.check.movement.FlyCheck;
import com.mejta.mejtaac.check.movement.GroundSpoofCheck;
import com.mejta.mejtaac.check.movement.TimerCheck;
import com.mejta.mejtaac.check.protocol.InvalidMoveCheck;
import com.mejta.mejtaac.check.protocol.MoveData;
import com.mejta.mejtaac.enforce.EnforcementPolicy;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;
import com.mejta.mejtaac.mixin.ServerGamePacketListenerImplAccessor;
import com.mejta.mejtaac.record.AcRecords;
import com.mejta.mejtaac.sync.EntityHistory;
import com.mejta.mejtaac.sync.TickSync;
import com.mejta.mejtaac.violation.Throttle;
import com.mejta.mejtaac.violation.ViolationTracker;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;

/**
 * Runs the pipeline for every piece of evidence: detect (checks) → assess (violation level,
 * exemptions) → decide (enforcement policy) → report (log, record, alert) → act (kick).
 * Each stage is configurable on its own and a failure in one never breaks packet handling.
 */
public final class AntiCheat {
	/** At most one violation record and one log line per player and check per second. */
	private static final long RECORD_INTERVAL_MS = 1_000;

	private static volatile AntiCheat instance;

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
		/** Server tick of the newest velocity change sent to this player (knockback, explosion...). */
		volatile int impulseTick = Integer.MIN_VALUE;
		volatile long impulseNanos;
		boolean impulsePending;
		/** Latest raw rotation from a movement packet, and whether one arrived this client tick. */
		float yaw;
		float pitch;
		final List<PendingAttack> pendingAttacks = new ArrayList<>();
		volatile boolean singleplayerOwner;
		volatile float msPerTick;
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
		AcConfig cfg = config;
		PlayerData pd = data(player.getUUID());
		pd.claimedOnGround = packet.isOnGround();
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

	/** Server thread, end of every server tick: entity history and sync pings, if a check needs them. */
	public void onServerTickEnd(MinecraftServer server) {
		if (config.needsSync()) {
			history.record(server, server.getTickCount());
			sync.sendPings(server);
		}
	}

	/** Network thread: one of our pongs arrived. */
	public void onPongArrival(UUID player, long nanos) {
		sync.recordArrival(player, nanos);
	}

	/** Server thread, in packet order: one of our pongs. */
	public void onPong(ServerPlayer player, int pongId) {
		sync.onPong(player.getUUID(), pongId);
	}

	/** Network thread: a client tick ended (timer accounting uses the arrival time). */
	public void onClientTickArrival(UUID player, long nanos, MinecraftServer server) {
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
		AcConfig cfg = config;
		PlayerData d = data(player.getUUID());
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
					sync.recentMaxRttMs(player.getUUID()), player.getY());
			if (flag.isPresent() && handle(player, flag.get(), cfg, cfg.groundSpoofKickVl())) {
				return true;
			}
		}
		if (evaluatePendingAttacks(player, d, cfg)) {
			return true;
		}
		return cfg.flyEnabled() && evaluateFly(player, d, cfg);
	}

	/**
	 * Server thread: the client attacked an entity, before vanilla handles it.
	 *
	 * @return {@code true} if the player was kicked and the attack should not be processed
	 */
	public boolean onAttack(ServerPlayer player, int entityId) {
		AcConfig cfg = config;
		if (!cfg.enabled() || player.isSpectator()) {
			return false;
		}
		PlayerData d = data(player.getUUID());
		Optional<Flag> multi = MultiTargetCheck.onAttack(d.multiTarget, entityId);
		if (cfg.multiTargetEnabled() && multi.isPresent() && handle(player, multi.get(), cfg, cfg.multiTargetKickVl())) {
			return true;
		}
		Entity target = player.level().getEntity(entityId);
		if (target == null || (!cfg.reachEnabled() && !cfg.hitboxEnabled())) {
			return false;
		}
		TargetView view = targetView(player, target);
		if (view == null) {
			return false;
		}
		if (cfg.hitboxEnabled() && d.pendingAttacks.size() < 16) {
			d.pendingAttacks.add(new PendingAttack(view.eyes(), view.candidates(),
					new HitboxCheck.Rotation(player.getYRot(), player.getXRot()), view.maxReach() + 1.0, view.synced()));
		}
		if (cfg.reachEnabled()) {
			Optional<Flag> reach = ReachCheck.evaluate(view.eyes(), view.candidates(), view.maxReach(), cfg.reachTolerance(),
					view.synced(), sync.rttMs(player.getUUID()));
			return reach.isPresent() && handle(player, reach.get(), cfg, cfg.reachKickVl());
		}
		return false;
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
			if (flag.isPresent() && handle(player, flag.get(), cfg, cfg.hitboxKickVl())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Server thread: the client started breaking a block, or used/placed against one.
	 *
	 * @return {@code true} if the player was kicked
	 */
	public boolean onBlockInteraction(ServerPlayer player, BlockPos pos, String action) {
		AcConfig cfg = config;
		if (!cfg.enabled() || !cfg.blockReachEnabled() || player.isSpectator()) {
			return false;
		}
		Optional<Flag> flag = BlockReachCheck.evaluate(eyes(player), new AABB(pos), player.blockInteractionRange(),
				cfg.blockReachTolerance(), action);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.blockReachKickVl());
	}

	/** Any thread: the server sent this player a velocity change (knockback, explosion, wind charge...). */
	public void onImpulseSent(ServerPlayer player) {
		PlayerData d = players.get(player.getUUID());
		if (d != null) {
			d.impulseTick = player.level().getServer().getTickCount();
			d.impulseNanos = System.nanoTime();
			d.impulsePending = true;
		}
	}

	private boolean evaluateFly(ServerPlayer player, PlayerData d, AcConfig cfg) {
		double y = player.getY();
		double dy = Double.isNaN(d.lastTickY) ? 0 : y - d.lastTickY;
		d.lastTickY = y;
		boolean untrusted = Double.isNaN(dy) || flyUntrusted(player, d, dy);
		Optional<Flag> flag = FlyCheck.onClientTick(d.fly, dy, player.getGravity(), untrusted);
		return flag.isPresent() && handle(player, flag.get(), cfg, cfg.flyKickVl());
	}

	/** Whether this tick's vertical movement cannot be predicted from the previous one. */
	private boolean flyUntrusted(ServerPlayer player, PlayerData d, double dy) {
		// A client that believes it stands on something (possibly a block it has not yet seen removed)
		// legitimately does not fall; lying about it is movement.ground_spoof's job.
		if (d.claimedOnGround || groundExempt(player) || player.getAbilities().mayfly && player.getAbilities().flying
				|| player.hasEffect(MobEffects.SLOW_FALLING) || player.isInPowderSnow || touchesSlowingBlock(player)) {
			return true;
		}
		if (d.impulsePending) {
			OptionalInt ack = sync.lastAckTick(player.getUUID());
			boolean applied = ack.isPresent() ? ack.getAsInt() >= d.impulseTick
					: System.nanoTime() - d.impulseNanos > 2_000_000_000L;
			if (applied) {
				d.impulsePending = false;
			}
			return true; // this tick is the first one with the new velocity (or still before it)
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

	/** Cobwebs, berry bushes, honey, scaffolding and bubble columns change vertical movement. */
	private static boolean touchesSlowingBlock(ServerPlayer player) {
		AABB box = player.getBoundingBox().inflate(0.1);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
				BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = player.level().getBlockState(pos);
			if (state.is(Blocks.COBWEB) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.HONEY_BLOCK)
					|| state.is(Blocks.SCAFFOLDING) || state.is(Blocks.BUBBLE_COLUMN) || state.is(Blocks.POWDER_SNOW)) {
				return true;
			}
		}
		return false;
	}

	private static boolean groundExempt(ServerPlayer player) {
		return player.isPassenger() || player.isSpectator() || player.isSleeping() || player.isDeadOrDying()
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
			double vl = violations.add(id, flag.checkId(), flag.vlIncrease(), cfg.decayPerMinute(), now);
			Action action = EnforcementPolicy.decide(cfg.enforcementMode(), cfg.enforcementMinConfidence(), flag, vl, kickThreshold);

			int suppressed = recordThrottle.tryPass(id, flag.checkId(), RECORD_INTERVAL_MS, now);
			boolean report = suppressed >= 0 || action != Action.NONE || cfg.logs(LogLevel.DEBUG);
			Detection detection = new Detection(id, name, player.level().dimension().identifier().toString(),
					player.connection.latency(), flag, vl, action, Math.max(suppressed, 0));

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
			if (action != Action.NONE) {
				return enforce(player, detection, cfg);
			}
			return false;
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
		if (cfg.alertsEnabled()) {
			alerts.enforcement(d);
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
		String more = d.suppressed() > 0 ? " (+" + d.suppressed() + " similar)" : "";
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
		if (!updated.timerEnabled() || !updated.enabled()) {
			players.values().forEach(d -> d.timer.reset());
		}
		if (!updated.flyEnabled() || !updated.enabled()) {
			players.values().forEach(d -> {
				d.fly.reset();
				d.lastTickY = Double.NaN;
			});
		}
		if (!previous.equals(updated)) {
			log.info("Configuration updated (enabled={}, enforcement={}, log={})", updated.enabled(),
					updated.enforcementMode(), updated.logLevel());
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
