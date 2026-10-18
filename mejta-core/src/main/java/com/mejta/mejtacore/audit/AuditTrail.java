package com.mejta.mejtacore.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.audit.AuditService;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.storage.RecordLogs;

/** {@link AuditService} writing to {@code mejta-data/mejtacore/audit.jsonl} and publishing notifications. */
public final class AuditTrail implements AuditService {
	public static final String LOG_NAME = "audit";
	/** 10 files of 5 MiB, kept for a year: audit history should outlive normal records. */
	public static final RecordLogOptions OPTIONS = new RecordLogOptions(5L * 1024 * 1024, 10, 365);

	private static final AuditTrail INSTANCE = new AuditTrail();

	private volatile AuditChain chain;

	private AuditTrail() {
	}

	public static AuditTrail get() {
		return INSTANCE;
	}

	@Override
	public void record(AuditEntry entry) {
		write(entry, null);
	}

	@Override
	public void record(AuditEntry entry, MejtaPermission staffAudience) {
		write(entry, java.util.Objects.requireNonNull(staffAudience, "staffAudience"));
	}

	private void write(AuditEntry entry, MejtaPermission audience) {
		// The line is linked and written on the I/O thread, so the chain always matches the file, a line that
		// could not be stored never becomes a link, and the caller never waits for disk.
		AuditChain c = chain();
		RecordLogs.appendBuilt(log(), reader -> {
			c.loadHead(() -> {
				List<JsonObject> newest = reader.recent(1, r -> r.has("hash"));
				return newest.isEmpty() ? null : newest.getFirst().get("hash").getAsString();
			});
			return c.prepare(entry);
		}, (line, stored) -> {
			if (stored) {
				c.commit(line);
			}
		});
		MejtaLog.CORE.info("[audit] {} {} {}{}: {}", entry.source(), entry.actor().name(), entry.action(),
				entry.targetName().isEmpty() ? "" : " " + entry.targetName(), entry.outcome());

		// Each addon's entries go out on <addon>.audit; IDs too long for a channel name share mejtacore.audit.
		String channel = entry.source() + ".audit";
		if (channel.length() > 64 || !Notification.CHANNEL_PATTERN.matcher(channel).matches()) {
			channel = MejtaCore.MOD_ID + ".audit";
		}
		Notification.Builder n = Notification.builder(entry.source(), channel)
				.severity(entry.outcome() == AuditOutcome.SUCCESS ? Severity.INFO : Severity.WARNING)
				.title(entry.actor().name() + " " + entry.action() + (entry.targetName().isEmpty() ? "" : " " + entry.targetName()))
				.message(entry.outcome().name())
				.timestamp(entry.timestamp())
				.field("source", entry.source())
				.field("actor", entry.actor().name())
				.field("action", entry.action())
				.field("target", entry.targetName())
				.field("outcome", entry.outcome().name());
		entry.targetUuid().ifPresent(uuid -> n.field("uuid", uuid));
		for (Map.Entry<String, String> d : entry.details().entrySet()) {
			n.field(d.getKey(), d.getValue());
		}
		if (audience != null) {
			n.inGameAudience(audience);
		}
		MejtaCoreAPI.notifications().publish(n.build());
	}

	private AuditChain chain() {
		AuditChain current = chain;
		if (current == null) {
			synchronized (this) {
				if (chain == null) {
					chain = new AuditChain(() -> null, line -> {
						throw new IllegalStateException("audit lines are written through RecordLogs.appendBuilt");
					});
				}
				current = chain;
			}
		}
		return current;
	}

	private static RecordLog log() {
		return RecordLogs.open(MejtaCore.MOD_ID, LOG_NAME, OPTIONS);
	}

	/** Reads the whole log (oldest first) and checks the chain. */
	public CompletableFuture<AuditChain.Verification> verify() {
		return log().readRecent(Integer.MAX_VALUE, r -> "audit".equals(r.has("type") ? r.get("type").getAsString() : ""))
				.thenApply(newestFirst -> {
					List<JsonObject> oldestFirst = new ArrayList<>(newestFirst);
					Collections.reverse(oldestFirst);
					return AuditChain.verify(oldestFirst, false);
				});
	}

	/** Whether the oldest kept line is the first one ever written (nothing rotated out). */
	public CompletableFuture<Boolean> startsAtGenesis() {
		return log().readRecent(Integer.MAX_VALUE, r -> r.has("prev")).thenApply(lines -> !lines.isEmpty()
				&& AuditChain.GENESIS.equals(lines.getLast().get("prev").getAsString()));
	}

	/** The newest {@code limit} entries, newest first. */
	public CompletableFuture<List<JsonObject>> recent(int limit) {
		return log().readRecent(limit, r -> r.has("hash"));
	}
}
