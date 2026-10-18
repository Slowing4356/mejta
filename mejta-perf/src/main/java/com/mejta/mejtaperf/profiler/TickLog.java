package com.mejta.mejtaperf.profiler;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The durations of the most recent server ticks, written by the server thread and read by the sampler thread
 * without locks. Ticks are numbered from 1 when they start; a slot holds one tick's number and duration and is
 * reused after {@link #SIZE} ticks.
 */
public final class TickLog {
	public static final int SIZE = 256;

	private final AtomicLongArray ids = new AtomicLongArray(SIZE);
	private final AtomicLongArray durations = new AtomicLongArray(SIZE);
	private volatile long current;

	/** Server thread, when a tick starts. Returns its number. */
	public long started() {
		return ++current;
	}

	/** Server thread, when the current tick ends. */
	public void ended(long nanos) {
		long id = current;
		int slot = (int) (id % SIZE);
		ids.set(slot, -1); // mark the slot as being written, so a reader never pairs one tick's id with another's time
		durations.set(slot, Math.max(0, nanos));
		ids.set(slot, id);
	}

	/** The number of the current (or last) tick; 0 before the first. */
	public long current() {
		return current;
	}

	/** How long tick {@code id} took, or -1 if it has not ended or is too old. */
	public long duration(long id) {
		int slot = (int) (id % SIZE);
		if (ids.get(slot) != id) {
			return -1;
		}
		long d = durations.get(slot);
		return ids.get(slot) == id ? d : -1;
	}
}
