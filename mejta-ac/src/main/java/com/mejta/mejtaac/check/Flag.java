package com.mejta.mejtaac.check;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one failed check evaluation: what was detected, how sure the check is, and the
 * measurements that led to it (kept for investigating false positives).
 *
 * @param checkId      stable check ID, e.g. {@code protocol.invalid_move}
 * @param reason       stable sub-reason, e.g. {@code impossible_pitch}
 * @param confidence   how sure the check is
 * @param vlIncrease   violation level added for this flag
 * @param measurements values the check looked at, in a stable order
 */
public record Flag(String checkId, String reason, Confidence confidence, double vlIncrease, Map<String, Object> measurements) {
	public Flag {
		Objects.requireNonNull(checkId, "checkId");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(confidence, "confidence");
		measurements = Collections.unmodifiableMap(new LinkedHashMap<>(measurements));
	}
}
