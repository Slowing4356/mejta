package com.mejta.mejtaac.enforce;

/**
 * Rules for movement mitigation: where a player may be set back to, and how often. Pure logic; the
 * caller supplies the server state.
 *
 * <p>The setback target is the player's position at the end of the newest client tick that was
 * <em>safe</em>: no movement prediction was building up an excess, no movement flag was raised, and
 * the position was a real, confirmed one (not a vehicle seat, not while a teleport or dimension change
 * is unconfirmed, not asleep or dead). Vanilla accepted that position, so it is reachable; the caller
 * still refuses a target that now collides with a block.
 *
 * <p>Setbacks use vanilla's own teleport, the one it uses to correct a player who "moved wrongly".
 * The client applies it after one round trip; until it confirms, vanilla ignores its movement, and
 * MejtaAC's movement checks treat the teleport like any other server-applied velocity.
 */
public final class Setbacks {
	/** At most one setback per player in this time (several checks may flag in the same tick). */
	public static final long MIN_INTERVAL_NANOS = 250_000_000L;
	/** A target farther away than this is stale (it should have been replaced long ago); not used. */
	public static final double MAX_DISTANCE = 64.0;

	private Setbacks() {
	}

	/**
	 * Whether this client tick's position may become the setback target.
	 *
	 * @param predictionsViolating whether any movement prediction is currently building up an excess
	 * @param flaggedThisTick      whether a movement check flagged in this client tick
	 * @param positionUnreliable   vehicle, sleeping, dead, removed, dimension change or an unconfirmed teleport
	 */
	public static boolean isSafeTick(boolean predictionsViolating, boolean flaggedThisTick, boolean positionUnreliable) {
		return !predictionsViolating && !flaggedThisTick && !positionUnreliable;
	}

	/**
	 * Whether a setback may be sent now.
	 *
	 * @param hasTarget            whether a safe position is known in the player's current level
	 * @param distance             from the player's current position to the target
	 * @param lastSetbackNanos     time of the previous setback ({@code Long.MIN_VALUE} if none)
	 * @param teleportUnconfirmed  whether vanilla still waits for the client to confirm a teleport
	 *                             (a previous setback, or vanilla's own correction): sending another one
	 *                             would only stack teleports
	 */
	public static boolean allowed(boolean hasTarget, double distance, long lastSetbackNanos, long nowNanos,
			boolean teleportUnconfirmed) {
		if (!hasTarget || teleportUnconfirmed || !(distance <= MAX_DISTANCE)) {
			return false;
		}
		return lastSetbackNanos == Long.MIN_VALUE || nowNanos - lastSetbackNanos >= MIN_INTERVAL_NANOS;
	}
}
