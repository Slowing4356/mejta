package com.mejta.example.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.google.gson.JsonObject;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.example.ExampleAddon;
import com.mejta.example.ExampleMod;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.client.MenuEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.network.ConfigPayloads;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.ui.ChestMenuScreen;
import com.mejta.mejtacore.ui.TextInputScreen;

/**
 * End-to-end test of MejtaCore driven through real client input: typed chat commands, mouse clicks
 * on menu slots, config files on disk. Every check is logged as {@code [MejtaTest] PASS/FAIL}.
 */
public final class MejtaCoreClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaTest");

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("mejta");
		resetToKnownState(ctx);

		registryChecks();
		persistenceChecks(configDir);
		permissionConfigChecks(configDir);
		recordLogChecks();
		notificationBusChecks(ctx);

		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			mainMenuChecks(ctx);
			coreSettingsChecks(ctx, configDir);
			addonSettingsChecks(ctx, configDir);
			commandChecks(ctx, configDir);
			addonMenuEntryChecks(ctx);
			pagingChecks(ctx);
			inGameNotificationChecks(ctx, world.getServer());
			noServerSettingsInSingleplayer(ctx);
		}

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
				TestServerConnection connection = server.connect()) {
			connection.getClientLevel().waitForChunksRender();
			multiplayerChecks(ctx);
			serverCommandChecks(ctx, server);
			remoteConfigChecks(ctx, server);
		}

		resetToKnownState(ctx);
		LOG.info("[MejtaTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaCore tests failed: " + String.join("; ", failures));
		}
	}

	// ---------------------------------------------------------------- checks

	private void registryChecks() {
		check("addon 'example' active", state("example") == AddonState.ACTIVE);
		check("addon 'example_extra' active", state("example_extra") == AddonState.ACTIVE);
		check("dependency initialized before dependent", ExampleMod.extraSawExampleActive);
		check("first 'dup_test' registration accepted", FailureCases.firstDuplicateAccepted);
		check("duplicate 'dup_test' registration rejected", !FailureCases.secondDuplicateAccepted);
		check("duplicate kept the first definition",
				MejtaCoreAPI.getAddon("dup_test").map(a -> a.name().equals("Duplicate Test #1")).orElse(false));
		check("missing dependency -> FAILED", state("needs_missing") == AddonState.FAILED
				&& reason("needs_missing").contains("missing required addon 'does_not_exist'"));
		check("exception in init -> FAILED", state("broken_init") == AddonState.FAILED
				&& reason("broken_init").contains("intentional failure"));
		check("dependent of failed addon -> FAILED", state("depends_on_broken") == AddonState.FAILED);
		check("dependency cycle -> both FAILED", state("cycle_a") == AddonState.FAILED
				&& state("cycle_b") == AddonState.FAILED && reason("cycle_a").contains("cycle"));
		check("invalid id rejected", FailureCases.invalidIdRejected);
		check("reserved id rejected", FailureCases.reservedIdRejected);
		check("self dependency rejected", FailureCases.selfDependencyRejected);
		check("late registration rejected",
				!MejtaCoreAPI.registerAddon(AddonDefinition.builder("too_late").build()));
		check("core keeps running despite failed addons", MejtaCoreAPI.isReady());
	}

	private void persistenceChecks(Path configDir) {
		ConfigHandle config = ExampleAddon.config();
		int launches = config.get(ExampleAddon.LAUNCH_COUNT);
		Path marker = configDir.resolve("mejtatest-last-launch.txt");
		try {
			if (Files.exists(marker)) {
				int previous = Integer.parseInt(Files.readString(marker).trim());
				check("launch_count persisted across restart (" + previous + " -> " + launches + ")", launches == previous + 1);
			} else {
				LOG.info("[MejtaTest] INFO first run: no previous launch count to compare (now {})", launches);
			}
			Files.writeString(marker, Integer.toString(launches));
		} catch (IOException | NumberFormatException e) {
			check("read launch marker", false);
		}
		String file = read(configDir.resolve("example.json"));
		check("example.json written with config_version 2", file.contains("\"config_version\": 2"));
		check("example.json contains launch_count " + launches, file.contains("\"launch_count\": " + launches));
		check("mejtacore.json exists", Files.exists(configDir.resolve("mejtacore.json")));
	}

	private void mainMenuChecks(ClientGameTestContext ctx) {
		chat(ctx, "/mejta gui");
		check("/mejta gui opens the menu", waitForMenu(ctx, "MejtaCore"));
		check("main menu layout", "core_settings".equals(entryId(ctx, 1)) && "installed_addons".equals(entryId(ctx, 3))
				&& "configuration".equals(entryId(ctx, 5)) && "about".equals(entryId(ctx, 7)));
		check("player inventory untouched", ctx.computeOnClient(mc -> mc.player.containerMenu == mc.player.inventoryMenu));
		ctx.takeScreenshot("mejtacore-01-main-menu");

		clickSlot(ctx, 3, 0);
		check("Installed Addons page opens", waitForMenu(ctx, "Installed Addons"));
		check("Installed Addons lists example", findSlot(ctx, "addon_example") >= 0);
		check("Installed Addons lists failed addon", findSlot(ctx, "addon_needs_missing") >= 0);
		check("Problems entry present", findSlot(ctx, "diagnostics") >= 0);
		ctx.takeScreenshot("mejtacore-02-installed-addons");

		clickSlot(ctx, findSlot(ctx, "diagnostics"), 0);
		check("Problems page opens", waitForMenu(ctx, "Problems"));
		clickSlot(ctx, navRow(ctx), 0);
		check("Back returns to Installed Addons", waitForMenu(ctx, "Installed Addons"));
		clickSlot(ctx, navRow(ctx), 0);
		check("Back returns to main menu", waitForMenu(ctx, "MejtaCore"));

		clickSlot(ctx, 5, 0);
		check("Configuration page opens", waitForMenu(ctx, "Configuration"));
		check("Configuration lists core and example configs",
				findSlot(ctx, "config_mejtacore") >= 0 && findSlot(ctx, "config_example") >= 0);
		clickSlot(ctx, navRow(ctx), 0);
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, 7, 0);
		check("About page opens", waitForMenu(ctx, "About MejtaCore"));
		ctx.takeScreenshot("mejtacore-03-about");

		closeMenu(ctx);
		check("Escape closes the menu", ctx.computeOnClient(mc -> mc.screen == null));

		chat(ctx, "/mejtacore");
		check("/mejtacore (no arguments) opens the menu", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
		chat(ctx, "/mejtacore gui");
		check("/mejtacore gui opens the menu", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
	}

	private void coreSettingsChecks(ClientGameTestContext ctx, Path configDir) {
		ConfigHandle core = MejtaCore.coreConfig();
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, 1, 0);
		check("MejtaCore Settings opens", waitForMenu(ctx, "MejtaCore Settings"));
		ctx.takeScreenshot("mejtacore-04-core-settings");

		int showIds = findSlot(ctx, "setting_show_addon_ids");
		clickSlot(ctx, showIds, 0);
		check("boolean toggles on click", core.get(CoreSettings.SHOW_ADDON_IDS));
		ctx.getInput().holdControl();
		clickSlot(ctx, showIds, 0);
		ctx.getInput().releaseControl();
		check("ctrl-click resets to default", !core.get(CoreSettings.SHOW_ADDON_IDS));
		clickSlot(ctx, showIds, 0);

		int sound = findSlot(ctx, "setting_menu_sound");
		clickSlot(ctx, sound, 0);
		check("enum cycles forward (NORMAL -> OFF)", core.get(CoreSettings.MENU_SOUND) == CoreSettings.MenuSound.OFF);
		clickSlot(ctx, sound, 1);
		check("enum cycles backward (OFF -> NORMAL)", core.get(CoreSettings.MENU_SOUND) == CoreSettings.MenuSound.NORMAL);

		int rows = findSlot(ctx, "setting_menu_rows");
		clickSlot(ctx, rows, 1);
		check("integer decreases on right-click (6 -> 5)", core.get(CoreSettings.MENU_ROWS) == 5);
		check("menu resizes after change", navRow(ctx) == 36);
		clickSlot(ctx, findSlot(ctx, "setting_menu_rows"), 0);
		clickSlot(ctx, findSlot(ctx, "setting_menu_rows"), 0);
		check("integer clamps at max (6)", core.get(CoreSettings.MENU_ROWS) == 6);

		check("change is dirty before close", core.isDirty());
		closeMenu(ctx);
		check("closing the menu saves", !core.isDirty()
				&& read(configDir.resolve("mejtacore.json")).contains("\"show_addon_ids\": true"));
	}

	private void addonSettingsChecks(ClientGameTestContext ctx, Path configDir) {
		ConfigHandle config = ExampleAddon.config();
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, 3, 0);
		waitForMenu(ctx, "Installed Addons");
		clickSlot(ctx, findSlot(ctx, "addon_example"), 0);
		check("addon detail page opens", waitForMenu(ctx, "Example Addon"));
		check("addon detail shows its own menu entries", findSlot(ctx, "example_status") >= 0);
		clickSlot(ctx, findSlot(ctx, "settings"), 0);
		check("addon settings page opens", waitForMenu(ctx, "Example Addon Settings"));

		ctx.getInput().holdShift();
		clickSlot(ctx, findSlot(ctx, "setting_radius"), 0);
		ctx.getInput().releaseShift();
		check("shift-click adds 10 (8 -> 18)", config.get(ExampleAddon.RADIUS) == 18);
		clickSlot(ctx, findSlot(ctx, "setting_speed"), 0);
		check("decimal step (1.0 -> 1.1)", config.get(ExampleAddon.SPEED) == 1.1);
		ctx.takeScreenshot("mejtacore-05-addon-settings");

		clickSlot(ctx, findSlot(ctx, "setting_greeting"), 0);
		check("string setting opens text input", waitForScreenClass(ctx, TextInputScreen.class));
		selectAllAndType(ctx, "Hi tester");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(2);
		check("text input returns to settings", waitForMenu(ctx, "Example Addon Settings"));
		check("string value saved in memory", "Hi tester".equals(config.get(ExampleAddon.GREETING)));

		clickSlot(ctx, findSlot(ctx, "setting_greeting"), 0);
		waitForScreenClass(ctx, TextInputScreen.class);
		selectAllAndType(ctx, "   ");
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(2);
		check("invalid string is not accepted", ctx.computeOnClient(mc -> mc.screen instanceof TextInputScreen));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		check("cancel returns to settings", waitForMenu(ctx, "Example Addon Settings"));
		check("value unchanged after cancel", "Hi tester".equals(config.get(ExampleAddon.GREETING)));

		ctx.getInput().holdControl();
		clickSlot(ctx, findSlot(ctx, "setting_speed"), 0);
		ctx.getInput().releaseControl();
		check("ctrl-click resets decimal", config.get(ExampleAddon.SPEED) == 1.0);

		closeMenu(ctx);
		String file = read(configDir.resolve("example.json"));
		check("addon settings saved on close", file.contains("\"radius\": 18") && file.contains("\"greeting\": \"Hi tester\""));
	}

	private void commandChecks(ClientGameTestContext ctx, Path configDir) {
		ConfigHandle config = ExampleAddon.config();

		clearChat(ctx);
		chat(ctx, "/mejtacore help");
		check("/mejtacore help lists built-in commands", chatContains(ctx, "/mejtacore config reload"));
		check("/mejtacore help lists addon subcommands", chatContains(ctx, "/mejtacore example status"));

		clearChat(ctx);
		chat(ctx, "/mejta help");
		check("/mejta help works", chatContains(ctx, "Commands (/mejta or /mejtacore)"));

		clearChat(ctx);
		chat(ctx, "/mejta addons");
		check("/mejta addons lists active addon", chatContains(ctx, "Example Addon 1.0.0 (example)"));
		check("/mejta addons shows failure reason", chatContains(ctx, "missing required addon 'does_not_exist'"));

		clearChat(ctx);
		chat(ctx, "/mejtacore about");
		check("/mejtacore about shows version", chatContains(ctx, "MejtaCore " + MejtaCore.version()));

		clearChat(ctx);
		chat(ctx, "/mejtacore example status");
		check("addon subcommand /mejtacore example status", chatContains(ctx, "Hi tester"));

		clearChat(ctx);
		chat(ctx, "/mejta example radius 42");
		check("addon subcommand under /mejta changes config", config.get(ExampleAddon.RADIUS) == 42
				&& read(configDir.resolve("example.json")).contains("\"radius\": 42"));

		Path example = configDir.resolve("example.json");
		write(example, read(example).replace("\"radius\": 42", "\"radius\": 7"));
		clearChat(ctx);
		chat(ctx, "/mejtacore config reload");
		check("config reload picks up external edit", config.get(ExampleAddon.RADIUS) == 7 && chatContains(ctx, "Reloaded"));

		write(example, read(example).replace("\"radius\": 7", "\"radius\": 999"));
		clearChat(ctx);
		chat(ctx, "/mejta config reload");
		check("out-of-range value falls back to default", config.get(ExampleAddon.RADIUS) == 8);
		check("reload reports the problem", chatContains(ctx, "problem(s) found"));
		check("file repaired after reload", read(example).contains("\"radius\": 8"));

		Path extra = configDir.resolve("example_extra.json");
		write(extra, "{ this is not json");
		chat(ctx, "/mejta config reload");
		check("corrupt file backed up", countBackups(configDir, "example_extra.json.broken-") > 0);
		check("corrupt file replaced with defaults", read(extra).contains("\"show_tips\": true"));

		clearChat(ctx);
		chat(ctx, "/mejtacore config");
		check("/mejtacore config without subcommand shows help", chatContains(ctx, "/mejtacore config reload"));
	}

	private void addonMenuEntryChecks(ClientGameTestContext ctx) {
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		int status = findSlot(ctx, "example_status");
		check("addon entry appears in main menu", status >= 9);
		clearChat(ctx);
		clickSlot(ctx, status, 0);
		check("addon entry click action runs", chatContains(ctx, "Hi tester"));
		clickSlot(ctx, findSlot(ctx, "example_page"), 0);
		check("addon can open its own page", waitForMenu(ctx, "Example Page"));
		clearChat(ctx);
		clickSlot(ctx, 0, 0);
		check("addon page entry works", chatContains(ctx, "Hello!"));
		clickSlot(ctx, navRow(ctx), 0);
		check("Back from addon page", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
	}

	private void pagingChecks(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> MejtaCore.coreConfig().set(CoreSettings.MENU_ROWS, 3));
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		int nav = navRow(ctx);
		check("3-row menu has navigation row at slot 18", nav == 18);
		check("page indicator shown when entries overflow", "nav_page".equals(entryId(ctx, nav + 4)));
		clickSlot(ctx, nav + 5, 0);
		check("next page shows addon entries", "example_status".equals(entryId(ctx, 0)));
		clickSlot(ctx, nav + 3, 0);
		check("previous page returns", "core_settings".equals(entryId(ctx, 1)));
		ctx.takeScreenshot("mejtacore-06-three-rows");
		closeMenu(ctx);
		ctx.runOnClient(mc -> MejtaCore.coreConfig().set(CoreSettings.MENU_ROWS, 6));
	}

	private void multiplayerChecks(ClientGameTestContext ctx) {
		check("connected to dedicated server", ctx.computeOnClient(mc -> mc.getSingleplayerServer() == null && mc.player != null));
		chat(ctx, "/mejta gui");
		check("menu opens on a dedicated server (non-op player)", waitForMenu(ctx, "MejtaCore"));
		ctx.takeScreenshot("mejtacore-07-multiplayer");
		closeMenu(ctx);
		clearChat(ctx);
		chat(ctx, "/mejtacore addons");
		check("client commands work on a dedicated server", chatContains(ctx, "Example Addon 1.0.0 (example)"));
	}

	// ---------------------------------------------------------------- helpers

	// ---------------------------------------------------------------- API version 2 checks

	private final List<Notification> captured = new CopyOnWriteArrayList<>();

	private void permissionConfigChecks(Path configDir) {
		String file = read(configDir.resolve(PermissionRegistry.CONFIG_ID + ".json"));
		check("API_VERSION is 3", MejtaCoreAPI.API_VERSION == 3);
		Setting<String> choice = Setting.choice("pick", "b", List.of("a", "b", "c"));
		check("choice setting accepts its options only", choice.isValid("c") && !choice.isValid("d") && choice.options().size() == 3);
		check("enum setting lists its constants as options", ExampleAddon.MODE.options().equals(List.of("NORMAL", "FAST", "SILENT")));
		check("permissions file lists config editing nodes", file.contains("\"mejtacore.config.edit\": \"ADMINS\"")
				&& file.contains("\"mejtacore.config.permissions\": \"OWNERS\""));
		check("permissions file lists core node with default level", file.contains("\"mejtacore.admin\": \"GAMEMASTERS\""));
		check("permissions file lists addon nodes", file.contains("\"example.ping\": \"ALL\"")
				&& file.contains("\"example.secret\": \"OWNERS\""));
		check("required level resolves to default", PermissionRegistry.requiredLevel(ExampleAddon.SECRET) == PermissionLevel.OWNERS);
		check("permission owned by another addon rejected",
				throwsIae(() -> AddonDefinition.builder("perm_test").permissions(ExampleAddon.PING)));
		check("invalid permission node rejected", throwsIae(() -> MejtaPermission.of("nodot", PermissionLevel.ALL)));
	}

	private void recordLogChecks() {
		Path dir = MejtaCoreAPI.dataDirectory(ExampleAddon.ID);
		deleteFiles(dir, "test-");
		check("data directory is mejta-data/example", dir.endsWith(Path.of("mejta-data", "example")));
		check("record log for unknown addon rejected",
				throwsIae(() -> MejtaCoreAPI.recordLog("nobody", "x", RecordLogOptions.DEFAULT)));
		check("invalid record log name rejected",
				throwsIae(() -> MejtaCoreAPI.recordLog(ExampleAddon.ID, "Bad Name", RecordLogOptions.DEFAULT)));

		RecordLog log = MejtaCoreAPI.recordLog(ExampleAddon.ID, "test-basic", RecordLogOptions.DEFAULT);
		check("same log returned when reopened", log == MejtaCoreAPI.recordLog(ExampleAddon.ID, "test-basic", RecordLogOptions.DEFAULT));
		for (int i = 1; i <= 3; i++) {
			JsonObject record = new JsonObject();
			record.addProperty("n", i);
			record.addProperty("player", i == 2 ? "Alex" : "Steve");
			log.append(record);
		}
		List<JsonObject> recent = await(log.readRecent(2, r -> true));
		check("readRecent returns newest first", recent != null && recent.size() == 2
				&& recent.get(0).get("n").getAsInt() == 3 && recent.get(1).get("n").getAsInt() == 2);
		check("records get a timestamp", recent != null && !recent.isEmpty() && recent.get(0).has("ts"));
		List<JsonObject> filtered = await(log.readRecent(10, r -> r.get("player").getAsString().equals("Steve")));
		check("readRecent filter", filtered != null && filtered.size() == 2);
		check("file is JSON lines", read(dir.resolve("test-basic.jsonl")).lines().count() == 3);

		JsonObject bad = new JsonObject();
		bad.addProperty("value", Double.NaN);
		long droppedBefore = log.droppedCount();
		boolean threw = false;
		try {
			log.append(bad);
		} catch (RuntimeException e) {
			threw = true;
		}
		check("unserializable record dropped, not thrown", !threw && log.droppedCount() == droppedBefore + 1);

		RecordLog rotating = MejtaCoreAPI.recordLog(ExampleAddon.ID, "test-rotate", new RecordLogOptions(64 * 1024, 2, 0));
		String padding = "x".repeat(1000);
		for (int i = 0; i < 200; i++) {
			JsonObject record = new JsonObject();
			record.addProperty("n", i);
			record.addProperty("pad", padding);
			rotating.append(record);
		}
		await(rotating.flush());
		check("log rotated to .1", Files.exists(dir.resolve("test-rotate.1.jsonl")));
		check("rotation keeps at most maxFiles", !Files.exists(dir.resolve("test-rotate.2.jsonl")));
		check("rotated files stay under the size limit", size(dir.resolve("test-rotate.jsonl")) <= 64 * 1024
				&& size(dir.resolve("test-rotate.1.jsonl")) <= 64 * 1024);
		List<JsonObject> newest = await(rotating.readRecent(1, r -> true));
		check("newest record survives rotation", newest != null && newest.size() == 1
				&& newest.get(0).get("n").getAsInt() == 199);
	}

	private void notificationBusChecks(ClientGameTestContext ctx) {
		NotificationService service = MejtaCoreAPI.notifications();
		check("built-in in-game provider registered", service.providerIds().contains("ingame"));
		check("built-in file log provider registered", service.providerIds().contains("file"));
		service.registerProvider(provider("test-failing", n -> {
			throw new IllegalStateException("intentional provider failure");
		}));
		service.registerProvider(new NotificationProvider() {
			@Override
			public String id() {
				return "test-capture";
			}

			@Override
			public boolean accepts(Notification notification) {
				return notification.channel().startsWith("example.");
			}

			@Override
			public void deliver(Notification notification) {
				captured.add(notification);
			}
		});
		check("duplicate provider id rejected", !service.registerProvider(provider("test-capture", n -> {
		})));

		service.publish(Notification.builder(ExampleAddon.ID, "example.bus")
				.severity(Severity.WARNING).title("Bus test").message("hello").field("k", "v").build());
		service.publish(Notification.builder(ExampleAddon.ID, "other.channel").title("Not for capture").build());
		check("provider receives notification despite a failing provider",
				waitUntil(ctx, () -> captured.stream().anyMatch(n -> n.title().equals("Bus test"))));
		check("accepts() filters channels", captured.stream().noneMatch(n -> n.title().equals("Not for capture")));
		check("fields and severity delivered", captured.stream().anyMatch(n -> n.title().equals("Bus test")
				&& n.severity() == Severity.WARNING && "v".equals(n.fields().get("k"))));
		check("invalid channel rejected", throwsIae(() -> Notification.builder(ExampleAddon.ID, "nodot")));
		check("every notification is written to the notification file log", waitUntil(ctx, () ->
				!notificationLog(r -> r.get("title").getAsString().equals("Not for capture")
						&& r.get("channel").getAsString().equals("other.channel")).isEmpty()));
		check("file log keeps severity and fields", !notificationLog(r -> r.get("title").getAsString().equals("Bus test")
				&& r.get("severity").getAsString().equals("WARNING")
				&& r.getAsJsonObject("fields").get("k").getAsString().equals("v")).isEmpty());
	}

	private static List<JsonObject> notificationLog(java.util.function.Predicate<JsonObject> filter) {
		List<JsonObject> found = MejtaCoreAPI.recordLog("mejtacore", "notifications", RecordLogOptions.DEFAULT)
				.readRecent(200, filter).join();
		return found == null ? List.of() : found;
	}

	private void inGameNotificationChecks(ClientGameTestContext ctx, TestServerContext server) {
		UUID player = ctx.computeOnClient(mc -> mc.player.getUUID());
		NotificationService service = MejtaCoreAPI.notifications();

		clearChat(ctx);
		service.publish(Notification.builder(ExampleAddon.ID, "example.notice")
				.title("Hello staff").message("in-game delivery").inGameAudience(ExampleAddon.PING).build());
		check("in-game provider shows notification in chat", waitUntil(ctx, () -> chatHas(ctx, "Hello staff")));

		service.setMuted(player, "example.notice", true);
		clearChat(ctx);
		service.publish(Notification.builder(ExampleAddon.ID, "example.notice")
				.title("Muted notice").inGameAudience(ExampleAddon.PING).build());
		syncNotifications(ctx, "sync-1");
		check("muted channel not shown", !chatHas(ctx, "Muted notice"));
		service.setMuted(player, "example.notice", false);

		boolean hostHasSecret = server.computeOnServer(s -> MejtaCoreAPI.hasPermission(
				s.getPlayerList().getPlayer(player), ExampleAddon.SECRET));
		clearChat(ctx);
		service.publish(Notification.builder(ExampleAddon.ID, "example.notice")
				.title("Owners only").inGameAudience(ExampleAddon.SECRET).build());
		syncNotifications(ctx, "sync-2");
		check("audience permission respected (host has OWNERS: " + hostHasSecret + ")",
				chatHas(ctx, "Owners only") == hostHasSecret);
	}

	/** Publishes a marker and waits until it is delivered, so earlier notifications are delivered too. */
	private void syncNotifications(ClientGameTestContext ctx, String marker) {
		MejtaCoreAPI.notifications().publish(Notification.builder(ExampleAddon.ID, "example.bus").title(marker).build());
		waitUntil(ctx, () -> captured.stream().anyMatch(n -> n.title().equals(marker)));
		ctx.waitTicks(5);
	}

	private void serverCommandChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		clearChat(ctx);
		chat(ctx, "/mejtaadmin");
		check("/mejtaadmin shows help on dedicated server", chatContains(ctx, "MejtaCore server commands"));
		check("/mejtaadmin help lists addon root", chatContains(ctx, "/mejtaadmin example ..."));
		check("/mejtaadmin help hides admin commands from non-op", !chatHas(ctx, "/mejtaadmin config reload"));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin example ping");
		check("addon server command runs (level ALL)", chatContains(ctx, "pong from server"));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin example secret");
		check("non-op denied OWNERS-level command", !chatHas(ctx, "secret ok"));

		clearChat(ctx);
		chat(ctx, "/mejtaadmin config reload");
		check("non-op denied config reload by default", !chatHas(ctx, "Reloaded"));

		ConfigHandle perms = MejtaCore.configs().find(PermissionRegistry.CONFIG_ID).orElseThrow();
		@SuppressWarnings("unchecked")
		Setting<PermissionLevel> admin = (Setting<PermissionLevel>) perms.spec().setting("mejtacore.admin").orElseThrow();
		perms.set(admin, PermissionLevel.ALL);
		perms.save();
		resendCommands(server);
		clearChat(ctx);
		chat(ctx, "/mejtaadmin config reload");
		check("permission override lets non-op reload configs", chatContains(ctx, "Reloaded"));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin addons");
		check("/mejtaadmin addons works with override", chatContains(ctx, "Example Addon 1.0.0 (example)"));

		perms.reset(admin);
		perms.save();
		resendCommands(server);
		clearChat(ctx);
		chat(ctx, "/mejtaadmin addons");
		check("resetting the override denies again", !chatHas(ctx, "Example Addon 1.0.0 (example)"));
	}

	// ---------------------------------------------------------------- remote config editing (API 3)

	private void noServerSettingsInSingleplayer(ClientGameTestContext ctx) {
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, findSlot(ctx, "configuration"), 0);
		waitForMenu(ctx, "Configuration");
		check("single-player: no Server Settings entry (local files are the server's)", findSlot(ctx, "server_settings") < 0);
		closeMenu(ctx);
	}

	private void remoteConfigChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		ConfigHandle example = ExampleAddon.config();
		ConfigHandle perms = MejtaCore.configs().find(PermissionRegistry.CONFIG_ID).orElseThrow();
		@SuppressWarnings("unchecked")
		Setting<PermissionLevel> edit = (Setting<PermissionLevel>) perms.spec().setting("mejtacore.config.edit").orElseThrow();
		int launches = example.get(ExampleAddon.LAUNCH_COUNT);
		example.resetAll();
		example.set(ExampleAddon.LAUNCH_COUNT, launches); // the next run's persistence check compares against it
		example.save();

		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, findSlot(ctx, "configuration"), 0);
		check("remote: Configuration page opens on a server", waitForMenu(ctx, "Configuration"));
		check("remote: Server Settings entry offered by a MejtaCore server", findSlot(ctx, "server_settings") >= 0);
		clickSlot(ctx, findSlot(ctx, "server_settings"), 0);
		check("remote: Server Settings page opens", waitForMenu(ctx, "Server Settings"));
		check("remote: non-op sees 'No permission'", waitUntil(ctx, () -> findSlot(ctx, "server_no_permission") >= 0));
		ctx.takeScreenshot("mejtacore-08-server-settings-denied");

		perms.set(edit, PermissionLevel.ALL);
		perms.save(); // a config change: the server pushes fresh catalogs, the open page refreshes
		check("remote: granting mejtacore.config.edit shows the server's configs",
				waitUntil(ctx, () -> findSlot(ctx, "server_config_example") >= 0));
		check("remote: permissions file hidden without mejtacore.config.permissions",
				findSlot(ctx, "server_config_mejtacore.permissions") < 0);
		ctx.takeScreenshot("mejtacore-09-server-settings");

		clickSlot(ctx, findSlot(ctx, "server_config_example"), 0);
		check("remote: server config page opens", waitForMenu(ctx, "Example Addon (Server)"));
		clickSlot(ctx, findSlot(ctx, "setting_radius"), 0);
		check("remote: left click changes the server's value (8 -> 9)", waitUntil(ctx, () -> example.get(ExampleAddon.RADIUS) == 9));
		check("remote: server saved the change to disk", waitUntil(ctx,
				() -> read(example.file()).contains("\"radius\": 9")));
		clickSlot(ctx, findSlot(ctx, "setting_mode"), 0);
		check("remote: enum setting cycles on the server (NORMAL -> FAST)",
				waitUntil(ctx, () -> example.get(ExampleAddon.MODE) == ExampleAddon.Mode.FAST));
		check("remote: page shows the server's value", waitUntil(ctx, () -> ctx.computeOnClient(mc -> {
			if (!(mc.screen instanceof ChestMenuScreen menu)) {
				return false;
			}
			int slot = findSlotOn(menu, "setting_radius");
			return slot >= 0 && menu.entryAt(slot).title().getString().contains("9");
		})));
		ctx.takeScreenshot("mejtacore-10-server-config-edit");
		closeMenu(ctx);

		ctx.runOnClient(mc -> ClientPlayNetworking.send(new ConfigPayloads.EditConfig("example", "SET", "radius", "999")));
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new ConfigPayloads.EditConfig("example", "SET", "greeting", "   ")));
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new ConfigPayloads.EditConfig("mejtacore.permissions", "SET",
				"mejtacore.config.permissions", "ALL")));
		ctx.waitTicks(20);
		check("remote: server rejects out-of-range values", example.get(ExampleAddon.RADIUS) == 9);
		check("remote: server applies the addon's own validator", !example.get(ExampleAddon.GREETING).isBlank());
		check("remote: non-owner cannot raise their own permissions",
				PermissionRegistry.requiredLevel(PermissionRegistry.CONFIG_PERMISSIONS) == PermissionLevel.OWNERS);

		clearChat(ctx);
		chat(ctx, "/mejtaadmin config set example radius 12");
		check("command: /mejtaadmin config set", waitUntil(ctx, () -> example.get(ExampleAddon.RADIUS) == 12));
		check("config change published on mejtacore.config (file log)", waitUntil(ctx, () -> !notificationLog(r ->
				r.get("channel").getAsString().equals("mejtacore.config")
						&& r.getAsJsonObject("fields").get("key").getAsString().equals("radius")
						&& r.get("message").getAsString().contains("12")).isEmpty()));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin config get example radius");
		check("command: /mejtaadmin config get", chatContains(ctx, "radius = 12"));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin config set example radius banana");
		check("command: invalid value explained", chatContains(ctx, "expected a whole number"));

		secretSettingChecks(ctx, perms);

		perms.reset(edit);
		perms.save();
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new ConfigPayloads.EditConfig("example", "SET", "radius", "20")));
		ctx.waitTicks(20);
		check("remote: edits refused after the permission is revoked", example.get(ExampleAddon.RADIUS) == 12);
		launches = example.get(ExampleAddon.LAUNCH_COUNT);
		example.resetAll();
		example.set(ExampleAddon.LAUNCH_COUNT, launches); // the next run's persistence check compares against it
		example.save();
	}

	/** API 4: secret settings never reach clients, chat, the log or notifications. Needs mejtacore.config.edit granted. */
	private void secretSettingChecks(ClientGameTestContext ctx, ConfigHandle perms) {
		ConfigHandle example = ExampleAddon.config();
		String token = "tok-" + UUID.randomUUID();
		@SuppressWarnings("unchecked")
		Setting<PermissionLevel> secrets = (Setting<PermissionLevel>) perms.spec().setting("mejtacore.config.secrets").orElseThrow();

		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSlot(ctx, findSlot(ctx, "configuration"), 0);
		waitForMenu(ctx, "Configuration");
		clickSlot(ctx, findSlot(ctx, "server_settings"), 0);
		waitUntil(ctx, () -> findSlot(ctx, "server_config_example") >= 0);
		clickSlot(ctx, findSlot(ctx, "server_config_example"), 0);
		check("secret: server config page opens", waitForMenu(ctx, "Example Addon (Server)"));
		check("secret: not sent to the client's Server Settings", findSlot(ctx, "setting_api_token") < 0
				&& findSlot(ctx, "setting_radius") >= 0);
		closeMenu(ctx);

		clearChat(ctx);
		chat(ctx, "/mejtaadmin config set example api_token " + token);
		ctx.waitTicks(10);
		check("secret: setting it needs mejtacore.config.secrets", example.get(ExampleAddon.API_TOKEN).isEmpty()
				&& chatContains(ctx, "mejtacore.config.secrets"));

		ctx.runOnClient(mc -> ClientPlayNetworking.send(new ConfigPayloads.EditConfig("example", "SET", "api_token", token)));
		ctx.waitTicks(20);
		check("secret: Server Settings packets cannot set it", example.get(ExampleAddon.API_TOKEN).isEmpty());

		perms.set(secrets, PermissionLevel.ALL);
		perms.save();
		clearChat(ctx);
		chat(ctx, "/mejtaadmin config set example api_token " + token);
		check("secret: set by command with the permission", waitUntil(ctx, () -> token.equals(example.get(ExampleAddon.API_TOKEN))));
		check("secret: feedback hides the value", chatContains(ctx, "api_token changed (value hidden)") && !chatHas(ctx, token));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin config get example api_token");
		check("secret: config get shows (set)", chatContains(ctx, "api_token = (set)") && !chatHas(ctx, token));
		check("secret: not in the main config file", waitUntil(ctx, () -> !read(example.file()).contains(token)
				&& !read(example.file()).contains("api_token")));
		check("secret: stored in secrets/example.json", read(example.file().resolveSibling("secrets").resolve("example.json"))
				.contains(token));
		check("secret: published change hides the value", waitUntil(ctx, () -> !notificationLog(r ->
				r.get("channel").getAsString().equals("mejtacore.config")
						&& r.getAsJsonObject("fields").get("key").getAsString().equals("api_token")).isEmpty())
				&& notificationLog(r -> r.toString().contains(token)).isEmpty());

		clearChat(ctx);
		chat(ctx, "/mejtaadmin config get example preview_feature");
		check("experimental: marked in config get", chatContains(ctx, "[experimental]"));

		example.reset(ExampleAddon.API_TOKEN);
		example.save();
		perms.reset(secrets);
		perms.save();
	}

	private static int findSlotOn(ChestMenuScreen menu, String entryId) {
		for (int slot = 0; slot < 54; slot++) {
			MenuEntry entry = menu.entryAt(slot);
			if (entry != null && entry.id().equals(entryId)) {
				return slot;
			}
		}
		return -1;
	}


	private static NotificationProvider provider(String id, java.util.function.Consumer<Notification> action) {
		return new NotificationProvider() {
			@Override
			public String id() {
				return id;
			}

			@Override
			public void deliver(Notification notification) {
				action.accept(notification);
			}
		};
	}

	private static void resendCommands(TestDedicatedServerContext server) {
		server.runOnServer(s -> {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				s.getCommands().sendCommands(p);
			}
		});
	}

	private static boolean chatHas(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text)));
	}

	private static boolean waitUntil(ClientGameTestContext ctx, BooleanSupplier condition) {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			ctx.waitTick();
		}
		return condition.getAsBoolean();
	}

	private static <T> T await(CompletableFuture<T> future) {
		try {
			return future.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			LOG.error("[MejtaTest] future failed", e);
			return null;
		}
	}

	private static boolean throwsIae(Runnable action) {
		try {
			action.run();
			return false;
		} catch (IllegalArgumentException e) {
			return true;
		}
	}

	private static long size(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			return Long.MAX_VALUE;
		}
	}

	private static void deleteFiles(Path dir, String prefix) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
				Files.deleteIfExists(file);
			}
		} catch (IOException e) {
			LOG.error("[MejtaTest] could not clean {}", dir, e);
		}
	}

	private void resetToKnownState(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			ConfigHandle core = MejtaCore.coreConfig();
			core.resetAll();
			core.save();
			ConfigHandle example = ExampleAddon.config();
			int launches = example.get(ExampleAddon.LAUNCH_COUNT);
			example.resetAll();
			example.set(ExampleAddon.LAUNCH_COUNT, launches);
			example.save();
		});
	}

	private void check(String name, boolean ok) {
		if (ok) {
			passed++;
			LOG.info("[MejtaTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaTest] FAIL {}", name);
		}
	}

	private static AddonState state(String id) {
		return MejtaCoreAPI.getAddon(id).map(AddonInfo::state).orElse(null);
	}

	private static String reason(String id) {
		return MejtaCoreAPI.getAddon(id).flatMap(AddonInfo::failureReason).orElse("");
	}

	private static void chat(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(command);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(3);
	}

	private static boolean waitForMenu(ClientGameTestContext ctx, String title) {
		try {
			ctx.waitFor(mc -> mc.screen instanceof ChestMenuScreen && mc.screen.getTitle().getString().equals(title), 40);
			return true;
		} catch (AssertionError | RuntimeException e) {
			LOG.error("[MejtaTest] expected menu '{}' but screen is {}", title,
					ctx.computeOnClient(mc -> mc.screen == null ? "none" : mc.screen.getClass().getSimpleName() + " '" + mc.screen.getTitle().getString() + "'"));
			return false;
		}
	}

	private static boolean waitForScreenClass(ClientGameTestContext ctx, Class<?> type) {
		try {
			ctx.waitFor(mc -> type.isInstance(mc.screen), 40);
			return true;
		} catch (AssertionError | RuntimeException e) {
			return false;
		}
	}

	private static void closeMenu(ClientGameTestContext ctx) {
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		ctx.waitTicks(2);
	}

	private static int navRow(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.screen instanceof ChestMenuScreen menu ? menu.navigationRowStart() : -1);
	}

	private static String entryId(ClientGameTestContext ctx, int slot) {
		return ctx.computeOnClient(mc -> {
			if (mc.screen instanceof ChestMenuScreen menu) {
				MenuEntry entry = menu.entryAt(slot);
				return entry == null ? null : entry.id();
			}
			return null;
		});
	}

	private static int findSlot(ClientGameTestContext ctx, String entryId) {
		return ctx.computeOnClient(mc -> {
			if (mc.screen instanceof ChestMenuScreen menu) {
				for (int slot = 0; slot < 54; slot++) {
					MenuEntry entry = menu.entryAt(slot);
					if (entry != null && entry.id().equals(entryId)) {
						return slot;
					}
				}
			}
			return -1;
		});
	}

	/** Moves the real cursor over a slot and presses a mouse button (0 left, 1 right, 2 middle). */
	private static void clickSlot(ClientGameTestContext ctx, int slot, int button) {
		if (slot < 0) {
			LOG.error("[MejtaTest] cannot click missing slot");
			return;
		}
		double[] pos = ctx.computeOnClient(mc -> {
			ChestMenuScreen menu = (ChestMenuScreen) mc.screen;
			double factor = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {(menu.slotX(slot) + 8) * factor, (menu.slotY(slot) + 8) * factor};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.getInput().pressMouse(button);
		ctx.waitTicks(2);
	}

	/**
	 * Clears the focused text box with Backspace, then types. (Ctrl+A cannot be used: Fabric's
	 * TestInput sends key events without modifier bits, and vanilla EditBox reads them from the event.)
	 */
	private static void selectAllAndType(ClientGameTestContext ctx, String text) {
		int length = ctx.computeOnClient(mc -> mc.screen instanceof TextInputScreen
				? ((TextInputScreen) mc.screen).currentValue().length() : 0);
		for (int i = 0; i < length; i++) {
			ctx.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}
		ctx.getInput().typeChars(text);
	}

	private static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
	}

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text)));
		if (!found) {
			LOG.error("[MejtaTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaCoreClientGameTest::chatLines));
		}
		return found;
	}

	@SuppressWarnings("unchecked")
	private static List<String> chatLines(Minecraft mc) {
		try {
			Field field = ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			List<GuiMessage> messages = (List<GuiMessage>) field.get(mc.gui.getChat());
			return messages.stream().map(m -> m.content().getString()).toList();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read chat", e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.writeString(file, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static long countBackups(Path dir, String prefix) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
		} catch (IOException e) {
			return 0;
		}
	}
}
