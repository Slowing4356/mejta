package com.mejta.mejtaperf.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Report files in one folder, named {@code <kind>-<UTC time>.txt}. Each is written to a temporary file and
 * moved into place, so a reader never sees half a report. Only the newest {@code keep} files of each kind are
 * kept.
 */
public final class ReportFiles {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

	private final Path dir;

	public ReportFiles(Path dir) {
		this.dir = dir;
	}

	/** Writes the report and deletes the oldest ones of the same kind beyond {@code keep}. Returns the file. */
	public Path write(String kind, Instant time, String text, int keep) throws IOException {
		if (!kind.matches("[a-z]+")) {
			throw new IllegalArgumentException("kind");
		}
		Files.createDirectories(dir);
		String base = kind + "-" + STAMP.format(time);
		Path target = dir.resolve(base + ".txt");
		for (int i = 2; Files.exists(target); i++) {
			target = dir.resolve(base + "-" + i + ".txt");
		}
		Path tmp = dir.resolve(target.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		prune(kind, keep);
		return target;
	}

	/** Reports of a kind, newest first. */
	public List<Path> list(String kind) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> {
				String name = p.getFileName().toString();
				return name.startsWith(kind + "-") && name.endsWith(".txt");
			}).forEach(files::add);
		}
		// Names sort by time once ".txt" is removed; a "-2" suffix then sorts after its base name, and is also later.
		files.sort((a, b) -> stem(b).compareTo(stem(a)));
		return files;
	}

	private static String stem(Path p) {
		String name = p.getFileName().toString();
		return name.substring(0, name.length() - ".txt".length());
	}

	private void prune(String kind, int keep) throws IOException {
		List<Path> files = list(kind);
		for (int i = Math.max(1, keep); i < files.size(); i++) {
			Files.deleteIfExists(files.get(i));
		}
	}
}
