package com.mejta.mejtaac.test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

/**
 * Test-only network conditions for the client's real TCP connection: a one-way delay in each
 * direction, random jitter, and temporary freezes. Installed closest to the socket, so it delays raw
 * bytes after encryption and compression, exactly like a slow network. Order is always preserved,
 * as with TCP.
 *
 * <p>Netty ships with Minecraft; this class exists only in the test source set.
 */
public final class LatencySimulator extends ChannelDuplexHandler {
	public static final String NAME = "mejtaac_test_latency";

	private record Pending(long deadline, Runnable action) {
	}

	private final Random random = new Random(42);
	private final ArrayDeque<Pending> inbound = new ArrayDeque<>();
	private final ArrayDeque<Pending> outbound = new ArrayDeque<>();
	private volatile long delayNanos;
	private volatile long jitterNanos;
	private volatile long frozenUntil;
	private boolean inboundDrainScheduled;
	private boolean outboundDrainScheduled;

	/** Installs the simulator on the client's current connection (or returns the installed one). */
	public static LatencySimulator install(Minecraft mc) {
		Channel channel = channel(mc.getConnection().getConnection());
		LatencySimulator existing = (LatencySimulator) channel.pipeline().get(NAME);
		if (existing != null) {
			return existing;
		}
		LatencySimulator simulator = new LatencySimulator();
		channel.pipeline().addFirst(NAME, simulator);
		return simulator;
	}

	/** One-way delay applied in each direction, so the round trip grows by twice this. */
	public void set(int oneWayDelayMs, int jitterMs) {
		delayNanos = TimeUnit.MILLISECONDS.toNanos(oneWayDelayMs);
		jitterNanos = TimeUnit.MILLISECONDS.toNanos(jitterMs);
	}

	/** Nothing passes in either direction for {@code ms}; then everything arrives in a burst. */
	public void freeze(int ms) {
		frozenUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		enqueue(ctx, inbound, () -> ctx.fireChannelRead(msg), true);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		enqueue(ctx, outbound, () -> ctx.writeAndFlush(msg, promise), false);
	}

	@Override
	public void flush(ChannelHandlerContext ctx) {
		// Every delayed write flushes itself when released.
	}

	// Event-loop thread only from here on.

	private void enqueue(ChannelHandlerContext ctx, ArrayDeque<Pending> queue, Runnable action, boolean in) {
		long now = System.nanoTime();
		long jitter = jitterNanos > 0 ? (long) ((random.nextDouble() * 2 - 1) * jitterNanos) : 0;
		long deadline = Math.max(now + delayNanos + jitter, frozenUntil);
		Pending last = queue.peekLast();
		if (last != null) {
			deadline = Math.max(deadline, last.deadline());
		}
		if (queue.isEmpty() && deadline <= now) {
			action.run();
			return;
		}
		queue.addLast(new Pending(deadline, action));
		scheduleDrain(ctx, queue, in);
	}

	private void scheduleDrain(ChannelHandlerContext ctx, ArrayDeque<Pending> queue, boolean in) {
		if (in ? inboundDrainScheduled : outboundDrainScheduled) {
			return;
		}
		Pending head = queue.peekFirst();
		if (head == null) {
			return;
		}
		if (in) {
			inboundDrainScheduled = true;
		} else {
			outboundDrainScheduled = true;
		}
		long wait = Math.max(0, head.deadline() - System.nanoTime());
		ctx.executor().schedule(() -> drain(ctx, queue, in), wait, TimeUnit.NANOSECONDS);
	}

	private void drain(ChannelHandlerContext ctx, ArrayDeque<Pending> queue, boolean in) {
		if (in) {
			inboundDrainScheduled = false;
		} else {
			outboundDrainScheduled = false;
		}
		long now = System.nanoTime();
		while (!queue.isEmpty() && queue.peekFirst().deadline() <= now) {
			queue.pollFirst().action().run();
		}
		scheduleDrain(ctx, queue, in);
	}

	private static Channel channel(Connection connection) {
		try {
			Field field = Connection.class.getDeclaredField("channel");
			field.setAccessible(true);
			return (Channel) field.get(connection);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot access the client channel", e);
		}
	}
}
