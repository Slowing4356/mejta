package com.mejta.mejtacore.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.logging.Diagnostics;

/**
 * {@link RecordLog} writing {@code <name>.jsonl} and rotating to {@code <name>.1.jsonl} ...
 * {@code <name>.<maxFiles-1>.jsonl}. Writer state is only touched on the I/O thread.
 */
final class JsonlRecordLog implements RecordLog {
	private static final Gson GSON = new Gson();

	private final String name;
	private final Path directory;
	private final RecordLogOptions options;
	private final ThreadPoolExecutor io;
	private final AtomicLong dropped = new AtomicLong();

	private BufferedWriter writer;
	private long currentSize;
	private boolean writeErrorReported;

	JsonlRecordLog(String name, Path directory, RecordLogOptions options, ThreadPoolExecutor io) {
		this.name = name;
		this.directory = directory;
		this.options = options;
		this.io = io;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public Path directory() {
		return directory;
	}

	RecordLogOptions options() {
		return options;
	}

	@Override
	public void append(JsonObject record) {
		Objects.requireNonNull(record, "record");
		JsonObject copy = record.deepCopy();
		if (!copy.has("ts")) {
			copy.addProperty("ts", Instant.now().toString());
		}
		// Gson writes NaN/Infinity as bare tokens, which is not valid JSON; reject such records instead.
		if (containsNonFinite(copy)) {
			dropped.incrementAndGet();
			Diagnostics.warn("storage", "Record with NaN/Infinity dropped from log '" + name + "' (store such values as strings)");
			return;
		}
		String line = GSON.toJson(copy);
		try {
			io.execute(() -> write(line));
		} catch (RejectedExecutionException e) {
			long count = dropped.incrementAndGet();
			if (count == 1 || count % 100 == 0) {
				Diagnostics.warn("storage", "I/O queue full, dropped " + count + " record(s) for log '" + name + "'");
			}
		}
	}

	/**
	 * A record built on the I/O thread just before it is written, in queue order. {@code build} gets the newest
	 * existing records lazily (see {@link RecordLogs.Reader}) and returns the record, or {@code null} to write nothing;
	 * {@code written} is told whether the line reached the file. Used by the audit chain, whose links must
	 * match the order on disk. When the queue is full this waits up to two seconds for room instead of
	 * dropping; called on the I/O thread itself, it runs at once.
	 *
	 * @return {@code false} if the record could not be queued
	 */
	boolean appendBuilt(java.util.function.Function<RecordLogs.Reader, JsonObject> build, java.util.function.BiConsumer<JsonObject, Boolean> written) {
		Runnable task = () -> {
			JsonObject record;
			try {
				record = build.apply(this::readNow);
			} catch (RuntimeException e) {
				Diagnostics.error("storage", "Could not build a record for log '" + name + "'", e);
				return;
			}
			if (record != null) {
				written.accept(record, write(GSON.toJson(record)));
			}
		};
		if (Thread.currentThread().getName().equals(RecordLogs.IO_THREAD_NAME)) {
			task.run();
			return true;
		}
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (true) {
			try {
				io.execute(task);
				return true;
			} catch (RejectedExecutionException e) {
				if (io.isShutdown() || System.nanoTime() > deadline) {
					long count = dropped.incrementAndGet();
					Diagnostics.warn("storage", "I/O queue full, dropped " + count + " record(s) for log '" + name + "'");
					return false;
				}
				java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
			}
		}
	}

	private List<JsonObject> readNow(int limit, Predicate<JsonObject> filter) throws IOException {
		flushWriter();
		List<JsonObject> out = new ArrayList<>();
		for (int i = 0; i < options.maxFiles() && out.size() < limit; i++) {
			Path file = file(i);
			if (!Files.exists(file)) {
				continue;
			}
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int j = lines.size() - 1; j >= 0 && out.size() < limit; j--) {
				JsonObject parsed = parse(lines.get(j));
				if (parsed != null && filter.test(parsed)) {
					out.add(parsed);
				}
			}
		}
		return out;
	}

	@Override
	public CompletableFuture<List<JsonObject>> readRecent(int limit, Predicate<JsonObject> filter) {
		Objects.requireNonNull(filter, "filter");
		return submit(() -> readNow(limit, filter));
	}

	@Override
	public CompletableFuture<Void> flush() {
		return submit(() -> {
			flushWriter();
			return null;
		});
	}

	@Override
	public long droppedCount() {
		return dropped.get();
	}

	private interface IoTask<T> {
		T run() throws IOException;
	}

	private <T> CompletableFuture<T> submit(IoTask<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		try {
			io.execute(() -> {
				try {
					future.complete(task.run());
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});
		} catch (RejectedExecutionException e) {
			future.completeExceptionally(e);
		}
		return future;
	}

	// ---------------------------------------------------------------- I/O thread only

	private boolean write(String line) {
		try {
			byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
			if (writer == null) {
				openWriter();
			}
			if (currentSize > 0 && currentSize + bytes.length > options.maxFileBytes()) {
				rotate();
			}
			writer.write(line);
			writer.write('\n');
			currentSize += bytes.length;
			if (io.getQueue().isEmpty()) {
				writer.flush();
			}
			writeErrorReported = false;
			return true;
		} catch (IOException e) {
			closeQuietly();
			if (!writeErrorReported) {
				writeErrorReported = true;
				Diagnostics.error("storage", "Could not write record log '" + name + "' in " + directory, e);
			}
			return false;
		}
	}

	private void openWriter() throws IOException {
		Files.createDirectories(directory);
		Path current = file(0);
		currentSize = Files.exists(current) ? Files.size(current) : 0;
		writer = Files.newBufferedWriter(current, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		if (currentSize > 0 && !endsWithNewline(current)) {
			// A crash left half a line: end it, so the next record starts on its own line.
			writer.write('\n');
			currentSize++;
		}
		pruneExpired();
	}

	private static boolean endsWithNewline(Path file) throws IOException {
		try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
			if (ch.size() == 0) {
				return true;
			}
			java.nio.ByteBuffer last = java.nio.ByteBuffer.allocate(1);
			ch.position(ch.size() - 1);
			ch.read(last);
			return last.get(0) == '\n';
		}
	}

	private void rotate() throws IOException {
		closeQuietly();
		Files.deleteIfExists(file(options.maxFiles() - 1));
		for (int i = options.maxFiles() - 2; i >= 0; i--) {
			Path from = file(i);
			if (Files.exists(from)) {
				Files.move(from, file(i + 1), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		openWriter();
	}

	private void pruneExpired() throws IOException {
		if (options.retentionDays() == 0) {
			return;
		}
		Instant cutoff = Instant.now().minus(options.retentionDays(), ChronoUnit.DAYS);
		for (int i = 1; i < options.maxFiles(); i++) {
			Path file = file(i);
			if (Files.exists(file) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
				Files.deleteIfExists(file);
			}
		}
	}

	private void flushWriter() throws IOException {
		if (writer != null) {
			writer.flush();
		}
	}

	private void closeQuietly() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// The next write reopens the file.
			}
			writer = null;
		}
	}

	private Path file(int index) {
		return directory.resolve(index == 0 ? name + ".jsonl" : name + "." + index + ".jsonl");
	}

	private static boolean containsNonFinite(JsonElement element) {
		if (element.isJsonPrimitive()) {
			JsonPrimitive p = element.getAsJsonPrimitive();
			return p.isNumber() && !Double.isFinite(p.getAsDouble());
		}
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				if (containsNonFinite(entry.getValue())) {
					return true;
				}
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				if (containsNonFinite(child)) {
					return true;
				}
			}
		}
		return false;
	}

	private static JsonObject parse(String line) {
		if (line.isBlank()) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(line);
			return element.isJsonObject() ? element.getAsJsonObject() : null;
		} catch (JsonParseException e) {
			return null;
		}
	}
}
