package com.mejta.mejtacorpse.data;

import java.util.UUID;

/** Who may open a corpse, and when one expires. Times are passed in (milliseconds). */
public final class Access {
	public enum Decision {
		/** The owner. */
		OWNER,
		/** Staff with the permission to open any corpse. */
		STAFF,
		/** Anyone, because the corpse has become public. */
		PUBLIC,
		DENIED
	}

	private Access() {
	}

	/**
	 * @param publicAfterMinutes {@code -1}: only the owner and staff, ever; {@code 0}: anyone at once;
	 *                           otherwise anyone once the corpse is this old
	 */
	public static Decision decide(UUID viewer, CorpseRecord corpse, boolean staff, int publicAfterMinutes, long nowMillis) {
		if (corpse.owner().equals(viewer)) {
			return Decision.OWNER;
		}
		if (staff) {
			return Decision.STAFF;
		}
		if (publicAfterMinutes >= 0 && nowMillis - corpse.createdMillis() >= publicAfterMinutes * 60_000L) {
			return Decision.PUBLIC;
		}
		return Decision.DENIED;
	}

	/** Milliseconds until the corpse becomes public, or {@code -1} if it never does. */
	public static long untilPublic(CorpseRecord corpse, int publicAfterMinutes, long nowMillis) {
		if (publicAfterMinutes < 0) {
			return -1;
		}
		return Math.max(0, corpse.createdMillis() + publicAfterMinutes * 60_000L - nowMillis);
	}

	/** Whether a corpse with items has reached {@code expireMinutes} ({@code 0}: never expires). */
	public static boolean expired(CorpseRecord corpse, int expireMinutes, long nowMillis) {
		return expireMinutes > 0 && nowMillis - corpse.createdMillis() >= expireMinutes * 60_000L;
	}
}
