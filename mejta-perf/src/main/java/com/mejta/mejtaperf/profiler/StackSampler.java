package com.mejta.mejtaperf.profiler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Samples one thread's stack at a fixed interval on its own daemon thread, like a Java-only Spark. Each
 * sample is filed under a first frame naming the phase it was taken in, {@link #IN_TICK} or
 * {@link #BETWEEN_TICKS} (when the server handles network packets and waits for the next tick).
 *
 * <p>Taking a stack briefly pauses the sampled thread at a safepoint; at the default 10 ms interval the cost is
 * small, but the profiler is still meant to run for minutes, not permanently.
 */
public final class StackSampler {
	public static final String IN_TICK = "[tick]";
	public static final String BETWEEN_TICKS = "[between ticks]";

	private static final Set<String> WAITS = Set.of("jdk.internal.misc.Unsafe.park", "java.lang.Thread.sleep0",
			"java.lang.Thread.sleepNanos0", "java.lang.Thread.sleep", "java.lang.Object.wait0", "java.lang.Object.wait",
			"java.lang.Thread.yield0", "java.lang.Thread.yield", "java.util.concurrent.locks.LockSupport.parkNanos",
			"java.util.concurrent.locks.LockSupport.park");

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
	private volatile End end = End.RUNNING;
	private volatile long samples;
	private volatile long finishedAt;
	private final Runnable onFinish;

	/**
	 * @param inTick whether the target is inside a server tick right now
	 * @param onFinish runs on the sampler thread once sampling has ended for any reason
	 */
	public StackSampler(Thread target, long intervalNanos, long limitNanos, BooleanSupplier inTick, LongSupplier clock, Runnable onFinish) {
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
			while (end == End.RUNNING) {
				long now = clock.getAsLong();
				if (now - startedAt >= limitNanos) {
					end = End.TIME_LIMIT;
					break;
				}
				if (!target.isAlive()) {
					end = End.THREAD_DIED;
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
					tree.add(frames);
					samples++;
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
			finishedAt = clock.getAsLong();
			onFinish.run();
		}
	}

	/** Asks sampling to end; the reason is recorded unless it already ended. */
	public void stop(End reason) {
		if (end == End.RUNNING) {
			end = reason;
		}
		LockSupport.unpark(worker);
	}

	/** Waits up to {@code millis} for the sampler thread to finish. */
	public boolean await(long millis) throws InterruptedException {
		worker.join(Math.max(1, millis));
		return !worker.isAlive();
	}

	public End end() {
		return end;
	}

	public long samples() {
		return samples;
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

	/** Whether a sample (outermost frame first, starting with the phase) was the server waiting, not working. */
	public static boolean isIdle(List<String> path) {
		if (path.isEmpty() || !WAITS.contains(path.get(path.size() - 1))) {
			return false;
		}
		if (path.get(0).equals(BETWEEN_TICKS)) {
			return true;
		}
		for (String frame : path) {
			if (frame.endsWith(".waitUntilNextTick") || frame.endsWith(".waitForTasks")) {
				return true;
			}
		}
		return false;
	}
}
