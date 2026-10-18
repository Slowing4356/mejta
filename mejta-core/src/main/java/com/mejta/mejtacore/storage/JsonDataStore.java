package com.mejta.mejtacore.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.util.SafeFiles;

/**
 * {@link DataStore} backed by one JSON file per key. Queued writes of the same key are coalesced:
 * only the newest value is written, and it inherits any durability promise of the values it replaced.
 */
public final class JsonDataStore implements DataStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String EXTENSION = ".json";
	/** Names Windows treats as devices, in any case and with any extension. */
	private static final Pattern WINDOWS_DEVICE = Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

	private final String owner;
	private final Path root;
	private final Executor io;
	private final int backups;

	/** Values not yet on disk, by key; {@code value == null} means a queued delete. Guarded by {@code this}. */
	private final Map<String, Pending> pending = new HashMap<>();
	private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
	private long sequence;

	private static final class Pending {
		final long seq;
		final JsonObject value;
		boolean durable;
		final List<CompletableFuture<Void>> waiting = new ArrayList<>();

		Pending(long seq, JsonObject value, boolean durable) {
			this.seq = seq;
			this.value = value;
			this.durable = durable;
		}
	}

	/**
	 * @param owner   addon ID, for diagnostics
	 * @param root    folder holding the files ({@code mejta-data/<addon>/store})
	 * @param io      single-threaded executor that runs queued writes in order
	 * @param backups previous versions kept per key (0 to 10)
	 */
	public JsonDataStore(String owner, Path root, Executor io, int backups) {
		if (backups < 0 || backups > 10) {
			throw new IllegalArgumentException("backups must be between 0 and 10");
		}
		this.owner = owner;
		this.root = root.toAbsolutePath().normalize();
		this.io = io;
		this.backups = backups;
	}

	public Path root() {
		return root;
	}

	@Override
	public Optional<JsonObject> read(String key) {
		Path file = fileFor(key);
		synchronized (this) {
			Pending p = pending.get(key);
			if (p != null) {
				return p.value == null ? Optional.empty() : Optional.of(p.value.deepCopy());
			}
		}
		synchronized (lock(key)) {
			return readFile(key, file);
		}
	}

	@Override
	public void write(String key, JsonObject value) {
		enqueue(key, copyOf(value), false);
	}

	@Override
	public CompletableFuture<Void> writeDurable(String key, JsonObject value) {
		return enqueue(key, copyOf(value), true);
	}

	@Override
	public void delete(String key) {
		enqueue(key, null, false);
	}

	@Override
	public void writeNow(String key, JsonObject value) throws IOException {
		Path file = fileFor(key);
		JsonObject copy = copyOf(value);
		Pending previous;
		Pending mine;
		synchronized (this) {
			previous = pending.get(key);
			mine = new Pending(++sequence, copy, true);
			if (previous != null) {
				mine.waiting.addAll(previous.waiting);
			}
			pending.put(key, mine);
		}
		try {
			synchronized (lock(key)) {
				writeFile(key, file, copy, true);
			}
		} catch (IOException | RuntimeException e) {
			synchronized (this) {
				if (pending.get(key) == mine) {
					if (previous != null) {
						pending.put(key, previous);
					} else {
						pending.remove(key);
					}
				}
			}
			if (previous != null) {
				// Its queued task may already have run and skipped itself because ours was newer.
				submit(key, previous.seq);
			}
			throw e instanceof IOException io ? io : new IOException(e);
		}
		finish(key, mine, null);
	}

	@Override
	public List<String> keys(String folder) {
		String prefix = folder.isEmpty() ? "" : folder + "/";
		Path dir = folder.isEmpty() ? root : fileFor(folder).resolveSibling(lastSegment(folder));
		// Pending writes are read before the folder: a write the I/O thread finishes in between is then
		// either still pending in the copy or already on disk, never missed.
		Map<String, Boolean> queued = new java.util.HashMap<>();
		synchronized (this) {
			for (Map.Entry<String, Pending> entry : pending.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0) {
					queued.put(key, entry.getValue().value != null);
				}
			}
		}
		TreeSet<String> keys = new TreeSet<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> files = Files.list(dir)) {
				files.forEach(f -> {
					String name = f.getFileName().toString();
					if (name.endsWith(EXTENSION) && Files.isRegularFile(f)) {
						String key = prefix + name.substring(0, name.length() - EXTENSION.length());
						if (DataStore.isValidKey(key)) {
							keys.add(key);
						}
					}
				});
			} catch (IOException e) {
				Diagnostics.warn(owner, "Could not list data folder " + dir + ": " + e);
			}
		}
		queued.forEach((key, present) -> {
			if (present) {
				keys.add(key);
			} else {
				keys.remove(key);
			}
		});
		return List.copyOf(keys);
	}

	@Override
	public CompletableFuture<Void> flush() {
		CompletableFuture<Void> done = new CompletableFuture<>();
		try {
			io.execute(() -> done.complete(null));
		} catch (RejectedExecutionException e) {
			done.complete(null); // writes that could not be queued were done synchronously
		}
		return done;
	}

	// ---------------------------------------------------------------- internals

	private CompletableFuture<Void> enqueue(String key, JsonObject value, boolean durable) {
		fileFor(key); // validates before anything is queued
		CompletableFuture<Void> future = new CompletableFuture<>();
		long seq;
		synchronized (this) {
			Pending previous = pending.get(key);
			Pending next = new Pending(++sequence, value, durable || (previous != null && previous.durable));
			if (previous != null) {
				next.waiting.addAll(previous.waiting);
			}
			next.waiting.add(future);
			pending.put(key, next);
			seq = next.seq;
		}
		submit(key, seq);
		return future;
	}

	private void submit(String key, long seq) {
		try {
			io.execute(() -> persist(key, seq));
		} catch (RejectedExecutionException e) {
			Diagnostics.warn(owner, "I/O queue full; writing data '" + key + "' on the calling thread");
			persist(key, seq);
		}
	}

	private void persist(String key, long seq) {
		Pending p;
		synchronized (this) {
			p = pending.get(key);
			if (p == null || p.seq != seq) {
				return; // superseded: the newer entry's own task writes it
			}
		}
		Throwable failure = null;
		try {
			synchronized (lock(key)) {
				synchronized (this) {
					if (pending.get(key) != p) {
						return; // a writeNow replaced it while we waited for the file: never write the older value over it
					}
				}
				if (p.value == null) {
					deleteFiles(fileFor(key));
				} else {
					writeFile(key, fileFor(key), p.value, p.durable);
				}
			}
		} catch (IOException | RuntimeException e) {
			failure = e;
			Diagnostics.error(owner, "Could not " + (p.value == null ? "delete" : "save") + " data '" + key + "'", e);
		}
		finish(key, p, failure);
	}

	private void finish(String key, Pending p, Throwable failure) {
		synchronized (this) {
			if (pending.get(key) == p && failure == null) {
				pending.remove(key);
			}
		}
		// A failed write stays pending in memory (reads keep seeing it) until a newer write succeeds.
		for (CompletableFuture<Void> future : p.waiting) {
			if (failure == null) {
				future.complete(null);
			} else {
				future.completeExceptionally(failure);
			}
		}
	}

	private Optional<JsonObject> readFile(String key, Path file) {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		Optional<JsonObject> main = parse(file);
		if (main.isPresent()) {
			return main;
		}
		try {
			Path broken = SafeFiles.backupBroken(file);
			Diagnostics.error(owner, "Data '" + key + "' was corrupt; moved to " + broken.getFileName());
		} catch (IOException e) {
			Diagnostics.error(owner, "Data '" + key + "' is corrupt and could not be moved aside", e);
		}
		for (int i = 1; i <= backups; i++) {
			Optional<JsonObject> backup = parse(backup(file, i));
			if (backup.isPresent()) {
				Diagnostics.warn(owner, "Using backup " + i + " of data '" + key + "'");
				try {
					// Put it back in place, so later reads (and keys()) find the data again.
					SafeFiles.writeAtomically(file, GSON.toJson(backup.get()) + System.lineSeparator());
				} catch (IOException e) {
					Diagnostics.error(owner, "Could not restore data '" + key + "' from its backup", e);
				}
				return backup;
			}
		}
		return Optional.empty();
	}

	private static Optional<JsonObject> parse(Path file) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return parsed.isJsonObject() ? Optional.of(parsed.getAsJsonObject()) : Optional.empty();
		} catch (IOException | JsonParseException | IllegalStateException e) {
			return Optional.empty();
		}
	}

	private void writeFile(String key, Path file, JsonObject value, boolean durable) throws IOException {
		String content = GSON.toJson(value) + System.lineSeparator();
		if (backups > 0 && Files.exists(file)) {
			Files.deleteIfExists(backup(file, backups));
			for (int i = backups - 1; i >= 1; i--) {
				Path from = backup(file, i);
				if (Files.exists(from)) {
					Files.move(from, backup(file, i + 1), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			// Copy, not move: the current file stays in place until the new one atomically replaces it.
			Files.copy(file, backup(file, 1), StandardCopyOption.REPLACE_EXISTING);
		}
		if (durable) {
			SafeFiles.writeDurably(file, content);
		} else {
			SafeFiles.writeAtomically(file, content);
		}
	}

	private void deleteFiles(Path file) throws IOException {
		Files.deleteIfExists(file);
		for (int i = 1; i <= backups; i++) {
			Files.deleteIfExists(backup(file, i));
		}
	}

	private static Path backup(Path file, int index) {
		return file.resolveSibling(file.getFileName() + ".bak" + index);
	}

	private Path fileFor(String key) {
		if (!DataStore.isValidKey(key)) {
			throw new IllegalArgumentException("Invalid data key '" + key + "'");
		}
		for (String segment : key.split("/")) {
			if (WINDOWS_DEVICE.matcher(segment.toLowerCase(Locale.ROOT)).matches()) {
				throw new IllegalArgumentException("Invalid data key '" + key + "': '" + segment + "' is a reserved name");
			}
		}
		Path file = root.resolve(key + EXTENSION).normalize();
		if (!file.startsWith(root)) {
			throw new IllegalArgumentException("Invalid data key '" + key + "'");
		}
		return file;
	}

	private static String lastSegment(String key) {
		int slash = key.lastIndexOf('/');
		return slash < 0 ? key : key.substring(slash + 1);
	}

	private Object lock(String key) {
		return fileLocks.computeIfAbsent(key, k -> new Object());
	}

	private static JsonObject copyOf(JsonObject value) {
		if (value == null) {
			throw new NullPointerException("value");
		}
		return value.deepCopy();
	}
}
