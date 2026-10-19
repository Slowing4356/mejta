package com.mejta.mejtacore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

/** The edit path shared by commands, the client's Server Settings and the server's chest menu. */
class ConfigEditingTest {
	enum Mode {
		NORMAL,
		FAST
	}

	static final Setting<Integer> RADIUS = Setting.integer("radius", 8, 1, 64);
	static final Setting<Mode> MODE = Setting.enumeration("mode", Mode.NORMAL);
	static final Setting<String> GREETING = Setting.string("greeting", "hi").validatedBy(t -> !t.isBlank());
	static final Setting<String> TOKEN = Setting.string("bot.token", "").secret();
	static final ConfigSpec SPEC = ConfigSpec.builder(1).add(RADIUS, MODE, GREETING, TOKEN).build();

	@TempDir
	Path dir;

	@Test
	void acceptedEditIsSavedAtOnce() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("edit", SPEC);
		ConfigEditing.Result result = ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "9", false);
		assertTrue(result.ok(), result.message());
		assertFalse(result.unsaved());
		assertEquals("edit: radius = 9", result.message());
		assertTrue(read(dir.resolve("edit.json")).contains("\"radius\": 9"));
		assertFalse(config.isDirty());

		assertTrue(ConfigEditing.apply(config, ConfigEditing.Op.SET, "mode", "fast", false).ok());
		assertEquals(Mode.FAST, new ConfigManager(dir).open("edit", SPEC).get(MODE), "survives a restart");

		assertTrue(ConfigEditing.apply(config, ConfigEditing.Op.RESET, "radius", "", false).ok());
		assertTrue(read(dir.resolve("edit.json")).contains("\"radius\": 8"));
	}

	@Test
	void refusalsExplainWhy() {
		ConfigHandle config = new ConfigManager(dir).open("refuse", SPEC);
		assertEquals("'99' is not allowed for radius (allowed: 1 to 64)",
				ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "99", false).message());
		assertEquals("'x' is not valid for radius: expected a whole number",
				ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "x", false).message());
		assertEquals("'   ' is not allowed for greeting", ConfigEditing.apply(config, ConfigEditing.Op.SET, "greeting", "   ", false).message());
		assertTrue(ConfigEditing.apply(config, ConfigEditing.Op.SET, "bot.token", "abc", false).message()
				.contains("secret setting"));
		assertEquals("Unknown setting 'nope' in refuse", ConfigEditing.apply(config, ConfigEditing.Op.SET, "nope", "1", false).message());
		assertEquals(8, config.get(RADIUS));
		assertEquals("", config.get(TOKEN));
	}

	/** Before 1.4.0 a change that could not be written was reported as done, and was gone after a restart. */
	@Test
	void failedSaveIsReported() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("locked", SPEC);
		// The temp file used for the atomic write cannot be created: a non-empty folder is in its place.
		Path blocker = dir.resolve("locked.json.tmp");
		Files.createDirectories(blocker);
		Files.writeString(blocker.resolve("keep"), "x");

		ConfigEditing.Result result = ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "12", false);
		assertFalse(result.ok());
		assertTrue(result.unsaved());
		assertTrue(result.message().startsWith("locked: radius = 12, but it could NOT be saved to locked.json"), result.message());
		assertEquals(12, config.get(RADIUS), "in effect until the restart");
		assertTrue(read(dir.resolve("locked.json")).contains("\"radius\": 8"));

		Files.delete(blocker.resolve("keep"));
		Files.delete(blocker);
		ConfigEditing.Result retry = ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "13", false);
		assertTrue(retry.ok(), retry.message());
		assertTrue(read(dir.resolve("locked.json")).contains("\"radius\": 13"));
	}

	@Test
	void saveOverHandEditsIsReported() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("handedit", SPEC);
		Path file = dir.resolve("handedit.json");
		Files.writeString(file, read(file).replace("\"radius\": 8", "\"radius\": 30"));
		assertTrue(ConfigEditing.apply(config, ConfigEditing.Op.SET, "mode", "FAST", false).ok());
		assertTrue(com.mejta.mejtacore.logging.Diagnostics.entries().stream().anyMatch(e -> e.subject().equals("config:handedit")
				&& e.message().contains("was edited on disk since it was loaded")));
		assertTrue(read(file).contains("\"radius\": 8"), "memory wins, as before; but no longer silently");

		long before = com.mejta.mejtacore.logging.Diagnostics.total();
		assertTrue(ConfigEditing.apply(config, ConfigEditing.Op.SET, "radius", "9", false).ok());
		assertEquals(before, com.mejta.mejtacore.logging.Diagnostics.total(), "no warning for our own earlier write");
	}

	@Test
	void resetAllKeepsSecretsForMenus() {
		ConfigHandle config = new ConfigManager(dir).open("all", SPEC);
		config.set(TOKEN, "secret-value");
		config.set(RADIUS, 20);
		config.save();
		ConfigEditing.Result result = ConfigEditing.apply(config, ConfigEditing.Op.RESET_ALL, null, null, false);
		assertTrue(result.ok());
		assertTrue(result.message().contains("1 secret setting(s) kept"));
		assertEquals(8, config.get(RADIUS));
		assertEquals("secret-value", config.get(TOKEN));
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}
}
