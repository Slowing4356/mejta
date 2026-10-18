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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Report files in one folder, named {@code <kind>-<UTC time>.txt}. Each is written to a temporary file and
 * moved into place, so a reader never sees half a report. Only the newest {@code keep} files of each kind are
 * kept.
 */
public final class ReportFiles {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	/** {@code <kind>-<stamp>} and an optional {@code -<n>} for a second file in the same second. */
	private static final Pattern NAME = Pattern.compile("(.*-\\d{8}-\\d{6})(?:-(\\d{1,9}))?");
	/** By time stamp, then by number: {@code -10} is later than {@code -9}, and both are later than no number. */
	private static final Comparator<Path> OLDEST_FIRST = Comparator.comparing((Path p) -> stamp(p)).thenComparingInt(ReportFiles::number);

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
		files.sort(OLDEST_FIRST.reversed());
		return files;
	}

	private static Matcher name(Path p) {
		String name = p.getFileName().toString();
		Matcher m = NAME.matcher(name.substring(0, name.length() - ".txt".length()));
		return m.matches() ? m : null;
	}

	/** The name up to its time stamp (UTC stamps sort by time as text); a name without one sorts as it is. */
	private static String stamp(Path p) {
		Matcher m = name(p);
		String name = p.getFileName().toString();
		return m != null ? m.group(1) : name.substring(0, name.length() - ".txt".length());
	}

	/** The {@code -<n>} suffix; the first file of a second has none and counts as 1. */
	private static int number(Path p) {
		Matcher m = name(p);
		return m == null || m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
	}

	private void prune(String kind, int keep) throws IOException {
		List<Path> files = list(kind);
		for (int i = Math.max(1, keep); i < files.size(); i++) {
			Files.deleteIfExists(files.get(i));
		}
	}
}
