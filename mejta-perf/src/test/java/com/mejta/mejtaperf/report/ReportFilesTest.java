package com.mejta.mejtaperf.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportFilesTest {
	@TempDir
	Path dir;

	@Test
	void writesAndKeepsTheNewest() throws IOException {
		ReportFiles files = new ReportFiles(dir.resolve("reports"));
		Instant t = Instant.parse("2026-10-18T20:00:00Z");
		for (int i = 0; i < 5; i++) {
			files.write("profile", t.plusSeconds(i), "profile " + i, 3);
		}
		Path same = files.write("profile", t.plusSeconds(4), "again", 3);
		assertEquals("profile-20261018-200004-2.txt", same.getFileName().toString());
		files.write("report", t, "report", 3);
		List<Path> profiles = files.list("profile");
		assertEquals(3, profiles.size());
		assertEquals(same, profiles.get(0), "newest first");
		assertEquals("profile 3", Files.readString(profiles.get(2)));
		assertEquals(1, files.list("report").size());
		try (var s = Files.list(dir.resolve("reports"))) {
			assertTrue(s.noneMatch(p -> p.toString().endsWith(".tmp")));
		}
		assertThrows(IllegalArgumentException.class, () -> files.write("../x", t, "", 1));
	}

	@Test
	void numberedFilesOfTheSameSecondSortByNumber() throws IOException {
		ReportFiles files = new ReportFiles(dir.resolve("same"));
		Instant t = Instant.parse("2026-10-18T20:00:00Z");
		for (int i = 1; i <= 11; i++) {
			files.write("report", t, "report " + i, 100);
		}
		files.write("report", t.plusSeconds(1), "later", 100);
		List<String> names = files.list("report").stream().map(p -> p.getFileName().toString()).toList();
		assertEquals(List.of("report-20261018-200001.txt", "report-20261018-200000-11.txt", "report-20261018-200000-10.txt",
				"report-20261018-200000-9.txt"), names.subList(0, 4), "-10 is newer than -9, not older than -2");
		assertEquals("report-20261018-200000.txt", names.get(names.size() - 1));
		files.write("report", t.plusSeconds(2), "newest", 3);
		assertEquals(List.of("newest", "later", "report 11"), files.list("report").stream().map(ReportFilesTest::read).toList(),
				"retention keeps the really newest");
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
