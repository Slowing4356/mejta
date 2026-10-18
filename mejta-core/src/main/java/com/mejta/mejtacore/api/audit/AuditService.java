package com.mejta.mejtacore.api.audit;

import com.mejta.mejtacore.api.permission.MejtaPermission;

/**
 * The shared, append-only audit log, {@code mejta-data/mejtacore/audit.jsonl}. Obtain it with
 * {@code MejtaCoreAPI.audit()}.
 *
 * <p>Each line carries the SHA-256 hash of the previous line ({@code prev}) and of itself
 * ({@code hash}), so {@code /mejtaadmin audit verify} can show whether a line was changed or removed.
 * This cannot stop someone with access to the files from deleting the whole log, but it makes edits
 * visible.
 *
 * <p>Every entry is also published as a notification on {@code <source>.audit}, so it reaches the
 * notification file log and providers such as MejtaDiscord. Safe on any thread, and never waits for the disk; only
 * when MejtaCore's I/O queue is full does it wait (up to two seconds) for room, so that an audit entry is not lost.
 *
 * @since API version 4
 */
public interface AuditService {
	/** Records the entry. Not shown in in-game chat. */
	void record(AuditEntry entry);

	/** Records the entry and shows it in chat to online players with {@code staffAudience}. */
	void record(AuditEntry entry, MejtaPermission staffAudience);
}
