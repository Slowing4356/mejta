package com.mejta.mejtaperf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Memory, garbage collection, CPU and threads of the Java process, from the standard management beans.
 * {@link #secondPassed()} keeps a minute of garbage collection totals so collections per minute can be shown.
 */
final class JvmStats {
	record Snapshot(long heapUsed, long heapCommitted, long heapMax, long gcCount, long gcMillis, double processCpu, double systemCpu,
			int threads, int processors) {
	}

	private record Gc(long nanos, long count, long millis) {
	}

	private final Deque<Gc> history = new ArrayDeque<>();

	void secondPassed() {
		long[] gc = gcTotals();
		history.addLast(new Gc(System.nanoTime(), gc[0], gc[1]));
		while (history.size() > 61) {
			history.removeFirst();
		}
	}

	/** Collections and their total pause time over the last minute (or since tracking began): {count, millis, seconds}. */
	double[] gcLastMinute() {
		if (history.size() < 2) {
			return new double[] {0, 0, 0};
		}
		Gc first = history.peekFirst();
		Gc last = history.peekLast();
		return new double[] {last.count - first.count, last.millis - first.millis, (last.nanos - first.nanos) / 1e9};
	}

	static Snapshot snapshot() {
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		long[] gc = gcTotals();
		double process = -1;
		double system = -1;
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				process = os.getProcessCpuLoad();
				system = os.getCpuLoad();
			}
		} catch (Throwable t) {
			// jdk.management is not part of every runtime; CPU load is then unknown.
		}
		return new Snapshot(heap.getUsed(), heap.getCommitted(), heap.getMax(), gc[0], gc[1], process, system,
				ManagementFactory.getThreadMXBean().getThreadCount(), Runtime.getRuntime().availableProcessors());
	}

	private static long[] gcTotals() {
		long count = 0;
		long millis = 0;
		List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
		for (GarbageCollectorMXBean bean : beans) {
			count += Math.max(0, bean.getCollectionCount());
			millis += Math.max(0, bean.getCollectionTime());
		}
		return new long[] {count, millis};
	}
}
