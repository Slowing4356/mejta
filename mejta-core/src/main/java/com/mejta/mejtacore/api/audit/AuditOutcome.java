package com.mejta.mejtacore.api.audit;

/** @since API version 4 */
public enum AuditOutcome {
	/** The action was carried out. */
	SUCCESS,
	/** Refused: missing permission, protected target, feature disabled... */
	DENIED,
	/** Attempted but failed (e.g. an I/O error); nothing or only part of it happened. */
	FAILED,
	/** Not carried out because the data changed in between (e.g. an offline edit against a newer save). */
	CONFLICT
}
