package com.mejta.mejtaac.check;

import java.util.Optional;

/**
 * A detection check. Checks only detect: they never alert, record or punish. Those steps are
 * handled by {@code AntiCheat} according to the configuration.
 *
 * <p>Implementations should be pure functions of their input (plus any per-player state passed in),
 * so they can be tested with both suspicious and legitimate inputs without a running server.
 *
 * @param <I> evidence the check evaluates, built from server-side data
 */
public interface Check<I> {
	/** Stable ID used in config keys, records and commands, e.g. {@code protocol.invalid_move}. */
	String id();

	CheckCategory category();

	/** Returns a flag if the input is suspicious, otherwise empty. */
	Optional<Flag> evaluate(I input);
}
