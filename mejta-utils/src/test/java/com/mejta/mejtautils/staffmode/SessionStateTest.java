package com.mejta.mejtautils.staffmode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mejta.mejtautils.staffmode.SessionState.JoinAction;

class SessionStateTest {
	@Test
	void crashWhileEntering() {
		// Snapshot written, nothing changed yet: forget the session, the player keeps everything.
		assertEquals(JoinAction.CANCEL_ENTRY, SessionState.ENTERING.onJoin(true, true, false));
		// Inventory already replaced by tools: staff mode continues; the snapshot is safe on disk.
		assertEquals(JoinAction.RESUME, SessionState.ENTERING.onJoin(true, false, true));
		// Something else: never guess.
		assertEquals(JoinAction.ASK, SessionState.ENTERING.onJoin(true, false, false));
	}

	@Test
	void crashWhileActive() {
		assertEquals(JoinAction.RESUME, SessionState.ACTIVE.onJoin(true, false, true));
		// The save still had the survival inventory (the crash came before the next autosave).
		assertEquals(JoinAction.RESUME, SessionState.ACTIVE.onJoin(true, true, false));
		assertEquals(JoinAction.ASK, SessionState.ACTIVE.onJoin(true, false, false));
	}

	@Test
	void crashWhileRestoringRepeatsTheReplacement() {
		// Restoration replaces the whole state, so doing it twice can neither lose nor duplicate items.
		assertEquals(JoinAction.REPEAT_RESTORE, SessionState.RESTORING.onJoin(true, false, true));
		assertEquals(JoinAction.REPEAT_RESTORE, SessionState.RESTORING.onJoin(true, true, false));
	}

	@Test
	void alreadyRestoredIsOnlyFinished() {
		// The survival inventory is back and saved: restoring again would re-grant items used meanwhile.
		assertEquals(JoinAction.FINISH_RESTORE, SessionState.RESTORING.onJoin(true, false, false, true));
		assertEquals(JoinAction.FINISH_RESTORE, SessionState.RESTORING.onJoin(true, true, true, true));
		// The snapshot is not used again, so its damage does not matter any more.
		assertEquals(JoinAction.FINISH_RESTORE, SessionState.RESTORING.onJoin(false, false, false, true));
		// Without the marker, nothing changes.
		assertEquals(JoinAction.REPEAT_RESTORE, SessionState.RESTORING.onJoin(true, false, false, false));
		// The marker only exists while restoring; elsewhere it is ignored.
		assertEquals(JoinAction.RESUME, SessionState.ACTIVE.onJoin(true, false, true, true));
		assertEquals(JoinAction.ASK, SessionState.ENTERING.onJoin(false, true, true, true));
	}

	@Test
	void damagedSnapshotAlwaysAsks() {
		for (SessionState state : SessionState.values()) {
			assertEquals(JoinAction.ASK, state.onJoin(false, true, true), state.name());
		}
	}

	@Test
	void restoreProblemsAreExplained() {
		assertTrue(RestoreCheck.problems(true, true, true).isEmpty());
		assertEquals(3, RestoreCheck.problems(false, false, false).size());
	}
}
