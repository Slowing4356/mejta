package com.mejta.mejtacore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.ConfigSpec;
import com.mejta.mejtacore.api.config.Setting;

class SecretSettingsTest {
	private static final String TOKEN = "xyz-not-a-real-token-123";

	static final Setting<String> BOT_TOKEN = Setting.string("bot.token", "").secret().describedAs("Bot token");
	static final Setting<String> GUILD = Setting.string("bot.guild", "");
	static final Setting<Integer> RETRIES = Setting.integer("retries", 3, 0, 10).experimental();
	static final ConfigSpec SPEC = ConfigSpec.builder(1).add(BOT_TOKEN, GUILD, RETRIES).build();

	@TempDir
	Path dir;

	@Test
	void flagsSurviveCopies() {
		Setting<String> s = Setting.string("a.webhook_url", "").secret().named("Webhook").describedAs("x").validatedBy(v -> true);
		assertTrue(s.isSecret());
		assertFalse(s.isExperimental());
		assertTrue(RETRIES.experimental().describedAs("y").isExperimental());
		assertThrows(IllegalStateException.class, () -> Setting.integer("n", 1).secret());
	}

	@Test
	void secretIsStoredOnlyInSecretsFile() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("discordtest", SPEC);
		config.set(BOT_TOKEN, TOKEN);
		config.set(GUILD, "123");
		config.save();

		String main = read(dir.resolve("discordtest.json"));
		String secrets = read(dir.resolve("secrets/discordtest.json"));
		assertFalse(main.contains(TOKEN), main);
		assertFalse(main.contains("bot.token"), main);
		assertTrue(main.contains("\"bot.guild\": \"123\""), main);
		assertTrue(secrets.contains(TOKEN), secrets);
		assertFalse(secrets.contains("bot.guild"), secrets);
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secrets/discordtest.json"))));
		}

		ConfigHandle again = new ConfigManager(dir).open("discordtest", SPEC);
		assertEquals(TOKEN, again.get(BOT_TOKEN));
		assertEquals("123", again.get(GUILD));
	}

	@Test
	void plainValueIsMovedToSecretsFile() throws IOException {
		Files.writeString(dir.resolve("old.json"), "{\"config_version\": 1, \"bot.token\": \"" + TOKEN + "\", \"retries\": 5}");
		ConfigHandle config = new ConfigManager(dir).open("old", SPEC);
		assertEquals(TOKEN, config.get(BOT_TOKEN));
		assertEquals(5, config.get(RETRIES));
		assertFalse(read(dir.resolve("old.json")).contains(TOKEN));
		assertTrue(read(dir.resolve("secrets/old.json")).contains(TOKEN));
	}

	@Test
	void brokenMainFileKeepsSecrets() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("broken", SPEC);
		config.set(BOT_TOKEN, TOKEN);
		config.save();
		Files.writeString(dir.resolve("broken.json"), "{ not json");
		config.reload();
		assertEquals(TOKEN, config.get(BOT_TOKEN));
		assertTrue(read(dir.resolve("secrets/broken.json")).contains(TOKEN));
	}

	@Test
	void missingMainFileKeepsSecrets() throws IOException {
		ConfigHandle config = new ConfigManager(dir).open("gone", SPEC);
		config.set(BOT_TOKEN, TOKEN);
		config.save();
		Files.delete(dir.resolve("gone.json"));
		config.reload();
		assertEquals(TOKEN, config.get(BOT_TOKEN));
	}

	@Test
	void editingNeverEchoesTheSecret() {
		ConfigHandle config = new ConfigManager(dir).open("edit", SPEC);
		ConfigEditing.Result denied = ConfigEditing.apply(config, ConfigEditing.Op.SET, "bot.token", TOKEN, false);
		assertFalse(denied.ok());
		assertEquals("", config.get(BOT_TOKEN));

		ConfigEditing.Result set = ConfigEditing.apply(config, ConfigEditing.Op.SET, "bot.token", TOKEN, true);
		assertTrue(set.ok());
		assertFalse(set.message().contains(TOKEN), set.message());
		assertEquals(TOKEN, config.get(BOT_TOKEN));

		ConfigEditing.Result resetAll = ConfigEditing.apply(config, ConfigEditing.Op.RESET_ALL, null, null, false);
		assertTrue(resetAll.ok());
		assertEquals(TOKEN, config.get(BOT_TOKEN), "a client's 'reset all' must not wipe secrets");

		assertEquals("(set)", SettingText.display(BOT_TOKEN, config.get(BOT_TOKEN)));
		assertEquals("(not set)", SettingText.display(BOT_TOKEN, ""));
	}

	@Test
	void secretLookingNamesAreSensitiveToo() {
		Setting<String> undeclared = Setting.string("webhook.alerts", "https://example.invalid/x");
		assertTrue(SettingText.isSensitive(undeclared));
		assertEquals("(set)", SettingText.display(undeclared, undeclared.defaultValue()));
		assertFalse(SettingText.isSensitive(GUILD));
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}
}
