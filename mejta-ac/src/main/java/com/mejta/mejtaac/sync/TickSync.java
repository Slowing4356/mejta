package com.mejta.mejtaac.sync;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Learns which server tick each client has processed, without any client mod.
 *
 * <p>At the end of every server tick, after that tick's entity updates were sent, each player gets a
 * {@link ClientboundPingPacket} whose ID encodes the tick. The vanilla client answers every ping
 * with a pong, in order, on its main thread; the vanilla server ignores pongs. So when a pong for
 * tick N arrives, the client had processed all of tick N's updates, and anything the client sends
 * after that pong (attacks, movement) was based on a world state of at least tick N.
 *
 * <p>Ordering: pongs are normally handled on the network thread, ahead of game packets queued for
 * the server thread. MejtaAC's pongs are rescheduled onto the server thread through vanilla's own
 * packet queue (see the mixin), so {@link #onPong} runs in exactly the order the client sent them,
 * interleaved correctly with attacks. Arrival times are taken on the network thread for an accurate
 * round-trip time.
 *
 * <p>Pings from other mods are left alone: only IDs carrying {@link #MARKER} are ours.
 */
public final class TickSync {
	/** High 16 bits of our ping IDs ("MA"); the low 16 bits are the server tick. */
	public static final int MARKER = 0x4D41_0000;
	private static final int MARKER_MASK = 0xFFFF_0000;
	private static final int SLOTS = 256;
	private static final int RTT_SAMPLES = 40;
	/** No pong for this long means we no longer know what the client sees. */
	private static final long SYNC_TIMEOUT_NANOS = 3_000_000_000L;

	private static final class PlayerSync {
		final long[] sentNanos = new long[SLOTS];
		final int[] sentTicks = new int[SLOTS];
		/** Arrival times recorded on the network thread, consumed in order on the server thread. */
		final Queue<Long> arrivals = new ConcurrentLinkedQueue<>();
		final int[] rttSamples = new int[RTT_SAMPLES];
		int sampleCount;
		int sampleIndex;
		double smoothedRtt = -1;
		int lastAckTick = Integer.MIN_VALUE;
		long lastAckNanos;
	}

	private final Map<UUID, PlayerSync> players = new ConcurrentHashMap<>();
	private volatile int currentTick;

	public static boolean isOurs(int pongId) {
		return (pongId & MARKER_MASK) == MARKER;
	}

	/** Server thread, end of tick: sends one ping per player. */
	public void sendPings(MinecraftServer server) {
		int tick = server.getTickCount();
		currentTick = tick;
		long now = System.nanoTime();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerSync sync = players.computeIfAbsent(player.getUUID(), k -> new PlayerSync());
			int slot = Math.floorMod(tick, SLOTS);
			sync.sentNanos[slot] = now;
			sync.sentTicks[slot] = tick;
			player.connection.send(new ClientboundPingPacket(MARKER | (tick & 0xFFFF)));
		}
	}

	/** Network thread: remembers when one of our pongs arrived. */
	public void recordArrival(UUID player, long nanos) {
		PlayerSync sync = players.get(player);
		if (sync != null) {
			sync.arrivals.add(nanos);
		}
	}

	/** Server thread, in stream order: the client has processed everything up to the ping's tick. */
	public void onPong(UUID player, int pongId) {
		PlayerSync sync = players.get(player);
		if (sync == null) {
			return;
		}
		Long arrival = sync.arrivals.poll();
		int tick = currentTick - ((currentTick - pongId) & 0xFFFF);
		int slot = Math.floorMod(tick, SLOTS);
		if (sync.sentTicks[slot] != tick) {
			return;
		}
		long now = System.nanoTime();
		int rtt = (int) Math.max(0, ((arrival != null ? arrival : now) - sync.sentNanos[slot]) / 1_000_000L);
		sync.rttSamples[sync.sampleIndex] = rtt;
		sync.sampleIndex = (sync.sampleIndex + 1) % RTT_SAMPLES;
		sync.sampleCount = Math.min(sync.sampleCount + 1, RTT_SAMPLES);
		sync.smoothedRtt = sync.smoothedRtt < 0 ? rtt : sync.smoothedRtt * 0.8 + rtt * 0.2;
		if (tick > sync.lastAckTick) {
			sync.lastAckTick = tick;
		}
		sync.lastAckNanos = now;
	}

	/** Newest server tick the client confirmed, if it answered recently. */
	public OptionalInt lastAckTick(UUID player) {
		PlayerSync sync = players.get(player);
		if (sync == null || sync.lastAckTick == Integer.MIN_VALUE || System.nanoTime() - sync.lastAckNanos > SYNC_TIMEOUT_NANOS) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(sync.lastAckTick);
	}

	/** Smoothed round-trip time in ms, or -1 if unknown. */
	public int rttMs(UUID player) {
		PlayerSync sync = players.get(player);
		return sync == null || sync.smoothedRtt < 0 ? -1 : (int) Math.round(sync.smoothedRtt);
	}

	/** Highest round-trip time among the last 40 samples (2 s), or -1 if unknown. */
	public int recentMaxRttMs(UUID player) {
		PlayerSync sync = players.get(player);
		if (sync == null || sync.sampleCount == 0) {
			return -1;
		}
		int max = 0;
		for (int i = 0; i < sync.sampleCount; i++) {
			max = Math.max(max, sync.rttSamples[i]);
		}
		return max;
	}

	public int currentTick() {
		return currentTick;
	}

	public void forget(UUID player) {
		players.remove(player);
	}

	/** Forgets everything, e.g. when the server stops (a new one counts ticks from 0 again). */
	public void clear() {
		players.clear();
		currentTick = 0;
	}
}
