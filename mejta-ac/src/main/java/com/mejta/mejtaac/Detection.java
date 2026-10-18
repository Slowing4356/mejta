package com.mejta.mejtaac;

import java.util.UUID;

import com.mejta.mejtaac.check.Flag;
import com.mejta.mejtaac.enforce.EnforcementPolicy.Action;

/**
 * Everything known about one flag after assessment, shared by records, alerts and logs.
 * Contains only the player's UUID and name: never IP addresses or other personal data.
 *
 * @param suppressed similar flags hidden by rate limiting since the last reported one
 */
public record Detection(UUID playerId, String playerName, String world, int pingMs, Flag flag, double violationLevel,
		Action action, int suppressed) {
}
