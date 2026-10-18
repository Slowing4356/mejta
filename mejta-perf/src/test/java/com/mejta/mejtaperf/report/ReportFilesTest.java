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
}
