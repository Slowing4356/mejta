package com.mejta.mejtaperf.profiler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Samples one thread's stack at a fixed interval on its own daemon thread, like a Java-only Spark. Each
 * sample is filed under a first frame naming the phase it was taken in, {@link #IN_TICK} or
 * {@link #BETWEEN_TICKS} (when the server handles network packets and waits for the next tick). The phase is
 * read just before the stack, so a sample taken as a tick starts or ends may carry the other one; {@link #isIdle}
 * therefore decides from the stack itself.
 *
 * <p>Taking a stack briefly pauses the sampled thread at a safepoint; at the default 10 ms interval the cost is
 * small, but the profiler is still meant to run for minutes, not permanently.
 */
public final class StackSampler {
	public static final String IN_TICK = "[tick]";
	public static final String BETWEEN_TICKS = "[between ticks]";

	/** JDK frames of a thread that is parked, sleeping or yielding, innermost first in a real stack. */
	private static final Set<String> WAITS = Set.of("jdk.internal.misc.Unsafe.park", "java.lang.Thread.sleep0",
			"java.lang.Thread.sleepNanos0", "java.lang.Thread.sleepNanos", "java.lang.Thread.sleep", "java.lang.Object.wait0",
			"java.lang.Object.wait", "java.lang.Thread.yield0", "java.lang.Thread.yield", "java.util.concurrent.locks.LockSupport.parkNanos",
			"java.util.concurrent.locks.LockSupport.park");
	/** The main loop's wait for the next tick. */
	private static final String WAIT_UNTIL_NEXT_TICK = "net.minecraft.server.MinecraftServer.waitUntilNextTick";
	/** The task loop's wait. Chunk loads and saves wait through the same method, so on its own it does not mean idle. */
	private static final String MANAGED_BLOCK = "net.minecraft.util.thread.BlockableEventLoop.managedBlock";
	private static final Set<String> WAIT_FOR_TASKS = Set.of("net.minecraft.server.MinecraftServer.waitForTasks",
			"net.minecraft.util.thread.BlockableEventLoop.waitForTasks");
	/** Frames of the server thread's own loop, outside any tick or task. */
	private static final Set<String> MAIN_LOOP = Set.of("java.lang.Thread.run", "java.lang.Thread.runWith",
			"net.minecraft.server.MinecraftServer.runServer", WAIT_UNTIL_NEXT_TICK, MANAGED_BLOCK,
			"net.minecraft.server.MinecraftServer.waitForTasks", "net.minecraft.util.thread.BlockableEventLoop.waitForTasks");

	/** Why sampling ended. */
	public enum End {
		RUNNING, STOPPED, TIME_LIMIT, CANCELLED, THREAD_DIED
	}

	private final Thread target;
	private final long intervalNanos;
	private final long limitNanos;
	private final BooleanSupplier inTick;
	private final LongSupplier clock;
	private final CallTree tree = new CallTree();
	private final Thread worker;
	private final long startedAt;
	private final AtomicReference<End> end = new AtomicReference<>(End.RUNNING);
	private volatile long samples;
	private volatile long finishedAt;
	private final Runnable onFinish;
	// Only-slow-ticks mode: samples of the current tick wait here until its duration is known.
	private final TickLog ticks;
	private final long slowNanos;
	private final java.util.ArrayList<String[]> pending = new java.util.ArrayList<>();
	private long pendingTick = -1;
	private volatile long keptSamples;
	private volatile long slowTicks;

	/**
	 * @param inTick whether the target is inside a server tick right now
	 * @param onFinish runs on the sampler thread once sampling has ended for any reason
	 */
	public StackSampler(Thread target, long intervalNanos, long limitNanos, BooleanSupplier inTick, LongSupplier clock, Runnable onFinish) {
		this(target, intervalNanos, limitNanos, inTick, clock, onFinish, null, 0);
	}

	/**
	 * Only keeps samples of ticks that took at least {@code slowNanos}, like Spark's {@code --only-ticks-over}: a
	 * tick's samples are held until the tick has ended and its duration is known from {@code ticks}. Samples
	 * between ticks are dropped. With {@code ticks == null} or {@code slowNanos <= 0} every sample is kept.
	 */
	public StackSampler(Thread target, long intervalNanos, long limitNanos, BooleanSupplier inTick, LongSupplier clock, Runnable onFinish,
			TickLog ticks, long slowNanos) {
		this.ticks = slowNanos > 0 ? ticks : null;
		this.slowNanos = slowNanos;
		this.target = target;
		this.intervalNanos = Math.max(1_000_000, intervalNanos);
		this.limitNanos = limitNanos;
		this.inTick = inTick;
		this.clock = clock;
		this.onFinish = onFinish;
		this.startedAt = clock.getAsLong();
		this.worker = new Thread(this::run, "MejtaPerf-Sampler");
		this.worker.setDaemon(true);
	}

	public void start() {
		worker.start();
	}

	private void run() {
		long next = clock.getAsLong();
		try {
			while (end.get() == End.RUNNING) {
				long now = clock.getAsLong();
				if (now - startedAt >= limitNanos) {
					end.compareAndSet(End.RUNNING, End.TIME_LIMIT);
					break;
				}
				if (!target.isAlive()) {
					end.compareAndSet(End.RUNNING, End.THREAD_DIED);
					break;
				}
				boolean tick = inTick.getAsBoolean();
				StackTraceElement[] stack = target.getStackTrace();
				if (stack.length > 0) {
					String[] frames = new String[Math.min(stack.length, CallTree.MAX_DEPTH - 1) + 1];
					frames[0] = tick ? IN_TICK : BETWEEN_TICKS;
					for (int i = 1; i < frames.length; i++) {
						StackTraceElement e = stack[stack.length - i];
						frames[i] = e.getClassName() + "." + e.getMethodName();
					}
					samples++;
					if (ticks == null) {
						tree.add(frames);
						keptSamples++;
					} else {
						keepIfSlow(tick, ticks.current(), frames);
					}
				}
				next += intervalNanos;
				long wait = next - clock.getAsLong();
				if (wait > 0) {
					LockSupport.parkNanos(wait);
				} else {
					next = clock.getAsLong(); // fell behind (a long safepoint); do not burst to catch up
				}
			}
		} finally {
			if (ticks != null) {
				settlePending(true);
			}
			finishedAt = clock.getAsLong();
			onFinish.run();
		}
	}

	/** Holds a sample of tick {@code tickId} until that tick's duration is known. */
	private void keepIfSlow(boolean inTick, long tickId, String[] frames) {
		if (pendingTick >= 0 && tickId != pendingTick) {
			settlePending(false);
		}
		if (!inTick) {
			settlePending(false); // the tick ended: its duration may be known now
			return;
		}
		if (pendingTick < 0) {
			pendingTick = tickId;
		}
		if (pending.size() < 100_000) {
			pending.add(frames);
		}
	}

	/** Commits or drops the held samples once their tick's duration is known; {@code last} gives up waiting. */
	private void settlePending(boolean last) {
		if (pendingTick < 0) {
			return;
		}
		long duration = ticks.duration(pendingTick);
		if (duration < 0 && !last && ticks.current() == pendingTick) {
			return; // still running
		}
		if (duration >= slowNanos) {
			for (String[] frames : pending) {
				tree.add(frames);
			}
			keptSamples += pending.size();
			slowTicks++;
		}
		pending.clear();
		pendingTick = -1;
	}

	/** Asks sampling to end; the reason is recorded unless it already ended (the first reason wins). */
	public void stop(End reason) {
		end.compareAndSet(End.RUNNING, reason);
		LockSupport.unpark(worker);
	}

	/** Waits up to {@code millis} for the sampler thread to finish. */
	public boolean await(long millis) throws InterruptedException {
		worker.join(Math.max(1, millis));
		return !worker.isAlive();
	}

	public End end() {
		return end.get();
	}

	public long samples() {
		return samples;
	}

	/** Samples in the report: all of them, or in only-slow-ticks mode those of slow ticks. */
	public long keptSamples() {
		return keptSamples;
	}

	/** Only-slow-ticks mode: the slow ticks whose samples were kept. */
	public long slowTicks() {
		return slowTicks;
	}

	/** The only-slow-ticks threshold, or 0 when every sample is kept. */
	public long slowNanos() {
		return ticks == null ? 0 : slowNanos;
	}

	public long intervalNanos() {
		return intervalNanos;
	}

	public long elapsedNanos() {
		long f = finishedAt;
		return (f != 0 ? f : clock.getAsLong()) - startedAt;
	}

	/** The merged samples. Read only after the sampler thread finished. */
	public CallTree tree() {
		return tree;
	}

	/**
	 * Whether a sample (outermost frame first, starting with the phase) was the server waiting for the next tick,
	 * not working. The innermost frames must be a wait (park, sleep, yield), and above them:
	 * <ul>
	 * <li>the main loop's wait: {@code MinecraftServer.waitUntilNextTick}, reached only through its
	 * {@code managedBlock} and {@code waitForTasks}. A chunk load or save waits in the same {@code managedBlock}
	 * and {@code waitForTasks}, but called from {@code ServerChunkCache.getChunk} or {@code ChunkMap.saveAllChunks}:
	 * that is lag, not idle. So is a packet task, run from the main loop's wait, that itself waits.</li>
	 * <li>or, between ticks, nothing but the server thread's own loop ({@code runServer}).</li>
	 * </ul>
	 * Any other wait (a lock, a future, a sleep in a mod) is time the server was held up, and counts as busy.
	 */
	public static boolean isIdle(List<String> path) {
		int i = path.size() - 1;
		if (i < 1 || !WAITS.contains(path.get(i))) {
			return false;
		}
		while (i > 0 && WAITS.contains(path.get(i))) {
			i--;
		}
		// Up from the wait: waitForTasks, at most one managedBlock, then the main loop's waitUntilNextTick.
		int blocks = 0;
		for (int j = i; j > 0; j--) {
			String frame = path.get(j);
			if (frame.equals(WAIT_UNTIL_NEXT_TICK)) {
				return true;
			}
			boolean block = frame.equals(MANAGED_BLOCK);
			if (block && ++blocks > 1 || !block && !WAIT_FOR_TASKS.contains(frame)) {
				break; // a second managedBlock, or any other caller: the wait belongs to someone else
			}
		}
		if (!path.get(0).equals(BETWEEN_TICKS)) {
			return false;
		}
		for (int j = i; j > 0; j--) {
			String frame = path.get(j);
			if (!MAIN_LOOP.contains(frame) && !frame.startsWith("net.minecraft.server.MinecraftServer.lambda$spin$")) {
				return false;
			}
		}
		return true;
	}
}
