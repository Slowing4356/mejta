package com.mejta.mejtaac.test;

import org.slf4j.Logger;

/**
 * Which suites a game test run covers. By default only the newest checks (stage 6) are tested end to end, in the
 * worst-case network profile: older checks are covered by unit tests and do not change when new ones are added.
 * {@code gradlew runClientGameTest -PfullGameTests} (or "full" in CI's manual run) runs every suite and profile.
 */
final class GameTestScope {
	static final boolean FULL = Boolean.getBoolean("mejta.fullGameTests");

	private GameTestScope() {
	}

	/** {@code true} (after logging that it is skipped) if a suite only runs in full runs. */
	static boolean skipUnlessFull(Logger log, String suite) {
		if (!FULL) {
			log.info("[MejtaAcTest] SKIP {}: older checks, run with -PfullGameTests", suite);
		}
		return !FULL;
	}
}
