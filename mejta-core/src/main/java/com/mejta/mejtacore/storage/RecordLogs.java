package com.mejta.mejtacore.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import com.mejta.mejtacore.addons.AddonRegistry;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;

/**
 * Owns the shared I/O thread, every open {@link RecordLog} and every {@link DataStore}. Data lives in
 * {@code <game dir>/mejta-data/<addon id>/}.
 */
public final class RecordLogs {
	public static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
	private static final int IO_QUEUE_CAPACITY = 8192;

	static final String IO_THREAD_NAME = "MejtaCore-IO";
	private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(IO_QUEUE_CAPACITY), runnable -> {
				Thread thread = new Thread(runnable, IO_THREAD_NAME);
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());

	private static final Map<String, JsonlRecordLog> LOGS = new HashMap<>();
	private static final Map<String, JsonDataStore> STORES = new HashMap<>();
	/** Previous versions kept per data-store key. */
	private static final int STORE_BACKUPS = 3;

	private RecordLogs() {
	}

	public static Path dataRoot() {
		return FabricLoader.getInstance().getGameDir().resolve("mejta-data");
	}

	public static Path dataDirectory(String ownerId) {
		requireOwner(ownerId);
		return dataRoot().resolve(ownerId);
	}

	public static synchronized RecordLog open(String ownerId, String name, RecordLogOptions options) {
		requireOwner(ownerId);
		if (name == null || !NAME_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid record log name '" + name + "': must match " + NAME_PATTERN.pattern());
		}
		String key = ownerId + "/" + name;
		JsonlRecordLog existing = LOGS.get(key);
		if (existing != null) {
			if (!existing.options().equals(options)) {
				Diagnostics.warn(ownerId, "Record log '" + name + "' already open with " + existing.options()
						+ "; ignoring " + options);
			}
			return existing;
		}
		JsonlRecordLog log = new JsonlRecordLog(name, dataDirectory(ownerId), options, IO);
		LOGS.put(key, log);
		MejtaLog.debug("Opened record log {} in {}", key, log.directory());
		return log;
	}

	/** The data store of {@code ownerId}: {@code mejta-data/<owner>/store/}. One instance per owner. */
	public static synchronized DataStore dataStore(String ownerId) {
		requireOwner(ownerId);
		return STORES.computeIfAbsent(ownerId, id -> new JsonDataStore(id, dataDirectory(id).resolve("store"), IO, STORE_BACKUPS));
	}

	/** Writes every pending record and data-store change, waiting at most {@code timeoutMs}. Called on shutdown. */
	public static void flushAll(long timeoutMs) {
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		synchronized (RecordLogs.class) {
			for (JsonlRecordLog log : LOGS.values()) {
				futures.add(log.flush());
			}
			for (JsonDataStore store : STORES.values()) {
				futures.add(store.flush());
			}
		}
		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			MejtaLog.CORE.warn("Could not flush all record logs within {} ms: {}", timeoutMs, e.toString());
		}
	}

	private static void requireOwner(String ownerId) {
		if (ownerId == null || !(ownerId.equals(MejtaCore.MOD_ID) || AddonRegistry.get().find(ownerId).isPresent())) {
			throw new IllegalArgumentException("Unknown addon '" + ownerId + "': register the addon before using its storage");
		}
	}

	/** Reads a log's newest records directly; only valid inside {@link #appendBuilt}. */
	public interface Reader {
		List<JsonObject> recent(int limit, java.util.function.Predicate<JsonObject> filter) throws java.io.IOException;
	}

	/**
	 * Appends a record that is built on the I/O thread, right before it is written and in queue order. Waits up to
	 * two seconds for room when the queue is full; on the I/O thread itself it runs at once. For records whose
	 * content depends on what was written before them (the audit hash chain).
	 *
	 * @param build returns the record, or {@code null} to write nothing
	 * @param written told whether the record reached the file
	 * @return {@code false} if the record could not be queued
	 */
	public static boolean appendBuilt(RecordLog log, java.util.function.Function<Reader, JsonObject> build,
			java.util.function.BiConsumer<JsonObject, Boolean> written) {
		return ((JsonlRecordLog) log).appendBuilt(build, written);
	}
}
