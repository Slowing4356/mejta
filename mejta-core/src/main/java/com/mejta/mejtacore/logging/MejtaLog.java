package com.mejta.mejtacore.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Central logging for MejtaCore. Internal: addons use {@code MejtaCoreAPI.logger(...)}. */
public final class MejtaLog {
	public static final Logger CORE = LoggerFactory.getLogger("MejtaCore");

	private static volatile boolean debug;

	private MejtaLog() {
	}

	public static Logger forAddon(String name) {
		return LoggerFactory.getLogger("MejtaCore/" + name);
	}

	public static void setDebug(boolean enabled) {
		debug = enabled;
	}

	public static boolean isDebug() {
		return debug;
	}

	/** Logs at INFO when the core "debug logging" setting is on, so it shows without changing log4j config. */
	public static void debug(String message, Object... args) {
		if (debug) {
			CORE.info("[debug] " + message, args);
		} else {
			CORE.debug(message, args);
		}
	}
}
