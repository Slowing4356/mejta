package com.mejta.mejtacore.api.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.google.gson.JsonObject;

/**
 * An append-only, size-bounded log of JSON records (one JSON object per line, {@code .jsonl}).
 * Open one with {@code MejtaCoreAPI.recordLog(addonId, name, options)}.
 *
 * <p>All disk I/O happens on MejtaCore's I/O thread, so these methods are safe to call from the
 * server thread. Files rotate by size ({@code name.jsonl}, {@code name.1.jsonl}, ...) and old files
 * are deleted according to {@link RecordLogOptions}.
 */
public interface RecordLog {
	String name();

	/** Folder holding this log's files. */
	Path directory();

	/**
	 * Queues a record for writing. A {@code "ts"} field (ISO-8601 instant) is added if missing.
	 * Never blocks and never throws for bad data: if the I/O queue is full, or the record cannot be
	 * written as valid JSON (it contains NaN or Infinity), it is dropped and counted in {@link #droppedCount()}.
	 */
	void append(JsonObject record);

	/**
	 * Reads up to {@code limit} matching records, newest first, across the current and rotated
	 * files. Runs on the I/O thread after earlier appends are written.
	 */
	CompletableFuture<List<JsonObject>> readRecent(int limit, Predicate<JsonObject> filter);

	/** Completes once every record appended so far is on disk. */
	CompletableFuture<Void> flush();

	long droppedCount();
}
