package com.mejta.mejtaperf.tick;

/**
 * Decides when a tick that has not ended yet is worth a warning: once it has run for longer than the sustain
 * time, and only once per tick. The lag alarm only learns about a tick when it ends, so a server stuck in one
 * tick (a deadlock, an endless loop) would otherwise never alert.
 *
 * <p>Used by one thread (MejtaPerf's watchdog); it reads the server thread's state only through the values it is
 * given.
 */
public final class StallDetector {
	private boolean reported;
	private long reportedStart;

	/**
	 * @param inTick whether the server thread is inside a tick
	 * @param tickStart when that tick started ({@link System#nanoTime()} scale)
	 * @param sustainNanos how long a tick may run before it is a stall
	 * @return how long the tick has run, in nanoseconds, when it should be reported now; otherwise -1
	 */
	public long check(boolean inTick, long tickStart, long now, long sustainNanos) {
		if (!inTick) {
			return -1;
		}
		long stuck = now - tickStart;
		if (stuck <= sustainNanos || reported && reportedStart == tickStart) {
			return -1;
		}
		reported = true;
		reportedStart = tickStart;
		return stuck;
	}
}
