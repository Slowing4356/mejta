package com.mejta.mejtasmp.antialt;

import java.util.Set;
import java.util.UUID;

/** The account limit's decision, kept apart from the game so it can be unit tested. */
public final class AltRules {
	public enum Decision {
		ALLOW,
		TOO_MANY_ONLINE,
		TOO_MANY_ACCOUNTS
	}

	private AltRules() {
	}

	/**
	 * @param othersOnline accounts already online from the same address (not counting {@code player})
	 * @param known        accounts that joined from the address before (may contain {@code player})
	 * @param maxAccounts  0 = no limit on different accounts
	 */
	public static Decision decide(UUID player, int othersOnline, int maxOnline, Set<UUID> known, int maxAccounts) {
		if (othersOnline >= maxOnline) {
			return Decision.TOO_MANY_ONLINE;
		}
		if (maxAccounts > 0 && !known.contains(player) && known.size() >= maxAccounts) {
			return Decision.TOO_MANY_ACCOUNTS;
		}
		return Decision.ALLOW;
	}
}
