package com.mejta.mejtautils.staffmode;

/**
 * Persisted state of a staff-mode session. Transitions are written to disk (durably) before the game
 * state changes, so a crash at any point leaves a state from which recovery is unambiguous or is
 * explicitly confirmed by a person.
 *
 * <pre>
 * INACTIVE --enter--> ENTERING --tools given--> ACTIVE --exit--> RESTORING --saved--> INACTIVE
 * </pre>
 */
public enum SessionState {
	/** The survival snapshot is on disk; the player may or may not have been changed yet. */
	ENTERING,
	/** The player is in staff mode. */
	ACTIVE,
	/** Restoration started; the player may or may not have the survival state back yet. */
	RESTORING;

	/** What to do when the player joins (or the server finds a session at startup). */
	public enum JoinAction {
		/** Give the tools again and continue staff mode. */
		RESUME,
		/** Replace the player's state with the snapshot again (idempotent). */
		REPEAT_RESTORE,
		/** Nothing was changed yet: forget the session. */
		CANCEL_ENTRY,
		/** It cannot be decided safely: change nothing and ask for confirmation. */
		ASK
	}

	/**
	 * @param snapshotIntact      the snapshot's hash matches its content and it can be decoded
	 * @param inventoryIsSnapshot the player's current inventory equals the saved survival inventory
	 * @param onlyStaffTools      the player's current inventory holds nothing but this session's staff tools
	 */
	public JoinAction onJoin(boolean snapshotIntact, boolean inventoryIsSnapshot, boolean onlyStaffTools) {
		if (!snapshotIntact) {
			return JoinAction.ASK;
		}
		return switch (this) {
			case ACTIVE -> onlyStaffTools || inventoryIsSnapshot ? JoinAction.RESUME : JoinAction.ASK;
			case RESTORING -> JoinAction.REPEAT_RESTORE;
			case ENTERING -> inventoryIsSnapshot ? JoinAction.CANCEL_ENTRY : onlyStaffTools ? JoinAction.RESUME : JoinAction.ASK;
		};
	}
}
