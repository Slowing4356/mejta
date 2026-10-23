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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.example.ExampleAddon;
import com.mejta.example.ExampleMod;
import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonDefinition;
import com.mejta.mejtacore.api.addon.AddonInfo;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.PagedMenu;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.api.server.Scheduler;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtacore.audit.AuditChain;
import com.mejta.mejtacore.audit.AuditTrail;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.api.storage.RecordLogOptions;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.network.ConfigPayloads;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.menu.ScreenMenus;
import com.mejta.mejtacore.ui.CardScreen;
import com.mejta.mejtacore.ui.EntryPageScreen;
import com.mejta.mejtacore.ui.MainMenuScreen;
import com.mejta.mejtacore.ui.MenuStatus;
import com.mejta.mejtacore.ui.ServerMenuScreen;
import com.mejta.mejtacore.ui.SettingsScreen;

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
			menuStyleCardChecks(ctx);
			inGameNotificationChecks(ctx, world.getServer());
			noServerSettingsInSingleplayer(ctx);
		}

		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer();
				TestServerConnection connection = server.connect()) {
			connection.getClientLevel().waitForChunksRender();
			multiplayerChecks(ctx);
			serverCommandChecks(ctx, server);
			remoteConfigChecks(ctx, server);
			api4Checks(ctx, server);
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
		check("main menu: overview with settings, menu style, addons and addon actions", menuHas(ctx,
				s -> s.cardIds().containsAll(List.of("settings", "server_menu_style", "addon:example", "example_status"))));
		check("main menu: sidebar lists every addon, Problems and About", menuHas(ctx, s -> s.sidebarPosition("addon:example") != null
				&& s.sidebarPosition("addon:needs_missing") != null && s.sidebarPosition("problems") != null && s.sidebarPosition("about") != null));
		check("player inventory untouched", ctx.computeOnClient(mc -> mc.player.containerMenu == mc.player.inventoryMenu));
		ctx.takeScreenshot("mejtacore-01-main-menu");

		clickCard(ctx, "addon:example");
		check("an addon card opens the addon's page", sectionIs(ctx, "addon:example"));
		check("the addon page has its settings and its own entries", menuHas(ctx,
				s -> s.cardIds().containsAll(List.of("settings", "example_status", "example_page"))));
		ctx.takeScreenshot("mejtacore-02-addon-page");
		clickSidebarEntry(ctx, "addon:needs_missing");
		check("a failed addon's page says why", sectionIs(ctx, "addon:needs_missing")
				&& menuHas(ctx, s -> String.join("|", s.headerLines()).contains("missing required addon 'does_not_exist'")));
		clickSidebarEntry(ctx, "problems");
		check("Problems lists the recorded problems", sectionIs(ctx, "problems") && menuHas(ctx, s -> s.cardIds().contains("problem_0")));
		clickSidebarEntry(ctx, "overview");

		clickCard(ctx, "settings");
		check("Settings opens the settings editor", waitForSettings(ctx));
		check("settings editor lists core and example configs", settingsHas(ctx,
				s -> s.listsConfig("mejtacore", false) && s.listsConfig("example", false)));
		check("settings editor opens on MejtaCore's settings", "mejtacore".equals(onSettings(ctx, SettingsScreen::selectedConfigId)));
		ctx.takeScreenshot("mejtacore-03b-settings-editor");
		clickSettingsButton(ctx, "done");
		check("Done returns to the main menu", waitForMenu(ctx, "MejtaCore") && sectionIs(ctx, "overview"));
		clickSidebarEntry(ctx, "about");
		check("About shows the versions", sectionIs(ctx, "about") && menuHas(ctx, s -> s.cardIds().containsAll(List.of("version", "minecraft"))));
		ctx.takeScreenshot("mejtacore-03-about");

		closeMenu(ctx);
		check("Escape closes the menu", ctx.computeOnClient(mc -> mc.screen == null));

		chat(ctx, "/mejtacore");
		check("/mejtacore (no arguments) opens the menu", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
		chat(ctx, "/mejta config example");
		check("/mejta config <config> opens the editor on that config", waitForSettings(ctx)
				&& "example".equals(onSettings(ctx, SettingsScreen::selectedConfigId)));
		closeMenu(ctx);
	}

	private void coreSettingsChecks(ClientGameTestContext ctx, Path configDir) {
		ConfigHandle core = MejtaCore.coreConfig();
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickCard(ctx, "settings");
		check("Settings opens the editor on MejtaCore's settings", waitForSettings(ctx)
				&& "mejtacore".equals(onSettings(ctx, SettingsScreen::selectedConfigId)));
		check("settings are split into categories", settingsHas(ctx, s -> s.listedGroups().contains("Afk")
				&& s.listedGroups().contains("General")));
		ctx.takeScreenshot("mejtacore-04-core-settings");

		clickSetting(ctx, "show_addon_ids", SettingsScreen.Part.CONTROL);
		check("toggle button switches a boolean", core.get(CoreSettings.SHOW_ADDON_IDS));
		check("toggle button shows the new value", "ON".equals(onSettings(ctx, s -> s.shownValue("show_addon_ids"))));
		clickSetting(ctx, "show_addon_ids", SettingsScreen.Part.RESET);
		check("reset button restores the default", !core.get(CoreSettings.SHOW_ADDON_IDS));
		clickSetting(ctx, "show_addon_ids", SettingsScreen.Part.CONTROL);

		clickSetting(ctx, "menu_sound", SettingsScreen.Part.CONTROL);
		check("option button cycles forward (NORMAL -> OFF)", core.get(CoreSettings.MENU_SOUND) == CoreSettings.MenuSound.OFF);
		ctx.getInput().holdShift();
		clickSetting(ctx, "menu_sound", SettingsScreen.Part.CONTROL);
		ctx.getInput().releaseShift();
		check("shift-click cycles backward (OFF -> NORMAL)", core.get(CoreSettings.MENU_SOUND) == CoreSettings.MenuSound.NORMAL);

		typeSetting(ctx, "afk.auto_minutes", "12");
		check("typing a number and Enter sets it (5 -> 12)", core.get(CoreSettings.AFK_AUTO_MINUTES) == 12);
		typeSetting(ctx, "afk.auto_minutes", "999");
		check("an out-of-range number is refused", core.get(CoreSettings.AFK_AUTO_MINUTES) == 12);
		check("and the range is explained", java.util.Objects.toString(onSettings(ctx, s -> s.errorOf("afk.auto_minutes"))).contains("0 to 120"));
		ctx.takeScreenshot("mejtacore-04b-validation");
		clickSetting(ctx, "afk.auto_minutes", SettingsScreen.Part.RESET);
		check("reset after an invalid value restores the default (5)", core.get(CoreSettings.AFK_AUTO_MINUTES) == 5
				&& onSettings(ctx, s -> s.errorOf("afk.auto_minutes")) == null);

		typeSearch(ctx, "addon id");
		check("search narrows the list", settingsHas(ctx, s -> s.listedKeys().equals(List.of("show_addon_ids"))));
		clearSearch(ctx);
		check("clearing the search lists everything again", settingsHas(ctx, s -> s.listedKeys().size() == core.spec().settings().size()));
		clickSettingsButton(ctx, "changed");
		check("the Show: Changed filter lists only changed settings", settingsHas(ctx, s -> s.listedKeys().equals(List.of("show_addon_ids"))));
		clickSettingsButton(ctx, "changed");

		check("change is dirty before close", core.isDirty());
		closeMenu(ctx);
		check("Escape closes the editor and saves", !core.isDirty()
				&& read(configDir.resolve("mejtacore.json")).contains("\"show_addon_ids\": true"));
		check("and returns to the menu it was opened from", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
	}

	private void addonSettingsChecks(ClientGameTestContext ctx, Path configDir) {
		ConfigHandle config = ExampleAddon.config();
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickSidebarEntry(ctx, "addon:example");
		check("the sidebar opens an addon's page", sectionIs(ctx, "addon:example"));
		clickCard(ctx, "settings");
		check("addon settings open in the editor", waitForSettings(ctx) && "example".equals(onSettings(ctx, SettingsScreen::selectedConfigId)));

		typeSetting(ctx, "radius", "18");
		check("number field sets an integer (8 -> 18)", config.get(ExampleAddon.RADIUS) == 18);
		check("the field shows the new value", "18".equals(onSettings(ctx, s -> s.shownValue("radius"))));
		typeSetting(ctx, "speed", "1.1");
		check("number field sets a decimal (1.0 -> 1.1)", config.get(ExampleAddon.SPEED) == 1.1);
		typeSetting(ctx, "speed", "fast");
		check("text in a number field is refused", config.get(ExampleAddon.SPEED) == 1.1
				&& java.util.Objects.toString(onSettings(ctx, s -> s.errorOf("speed"))).contains("not a number"));
		ctx.takeScreenshot("mejtacore-05-addon-settings");

		typeSetting(ctx, "greeting", "Hi tester");
		check("text field sets a string", "Hi tester".equals(config.get(ExampleAddon.GREETING)));
		typeSetting(ctx, "greeting", "   ");
		check("invalid string is not accepted", "Hi tester".equals(config.get(ExampleAddon.GREETING))
				&& onSettings(ctx, s -> s.errorOf("greeting")) != null);
		clickSetting(ctx, "speed", SettingsScreen.Part.RESET);
		check("reset button resets a decimal", config.get(ExampleAddon.SPEED) == 1.0);
		check("leaving an invalid field shows the value again", "Hi tester".equals(onSettings(ctx, s -> s.shownValue("greeting"))));

		clickSetting(ctx, "enabled", SettingsScreen.Part.CONTROL);
		check("toggle before undo", !config.get(ExampleAddon.ENABLED));
		clickSettingsButton(ctx, "undo");
		check("Undo Changes puts back everything changed in the editor", config.get(ExampleAddon.ENABLED)
				&& config.get(ExampleAddon.RADIUS) == 8 && config.get(ExampleAddon.GREETING).startsWith("Hello from"));
		typeSetting(ctx, "radius", "18");
		typeSetting(ctx, "greeting", "Hi tester");

		closeMenu(ctx);
		check("Escape returns to the addon page", waitForMenu(ctx, "MejtaCore") && sectionIs(ctx, "addon:example"));
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

		chat(ctx, "/mejtacore config");
		check("/mejtacore config without subcommand opens the settings editor", waitForSettings(ctx));
		closeMenu(ctx);
	}

	private void addonMenuEntryChecks(ClientGameTestContext ctx) {
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		check("addon entry appears in the main menu", menuHas(ctx, s -> s.cardIds().contains("example_status")));
		clearChat(ctx);
		clickCard(ctx, "example_status");
		check("addon entry click action runs", chatContains(ctx, "Hi tester"));
		clickCard(ctx, "example_page");
		check("addon can open its own page, drawn as cards", waitForMenu(ctx, "Example Page")
				&& ctx.computeOnClient(mc -> mc.screen instanceof EntryPageScreen));
		clearChat(ctx);
		clickCard(ctx, "hello");
		check("addon page entry works", chatContains(ctx, "Hello!"));
		clickFooter(ctx, "back");
		check("Back from addon page", waitForMenu(ctx, "MejtaCore"));
		closeMenu(ctx);
	}

	/** 1.7.0: the overview's card switches server menus between screens and chests. */
	private void menuStyleCardChecks(ClientGameTestContext ctx) {
		ConfigHandle core = MejtaCore.coreConfig();
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		check("server menus are chests by default", !core.get(CoreSettings.SERVER_MENU_SCREENS)
				&& menuHas(ctx, s -> s.card("server_menu_style").title().getString().endsWith("Chests")));
		clickCard(ctx, "server_menu_style");
		check("the menu style card switches to screens", core.get(CoreSettings.SERVER_MENU_SCREENS)
				&& menuHas(ctx, s -> s.card("server_menu_style").title().getString().endsWith("Screens")));
		clickCard(ctx, "server_menu_style");
		check("and back to chests", !core.get(CoreSettings.SERVER_MENU_SCREENS));
		closeMenu(ctx);
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
		check("API_VERSION is 6", MejtaCoreAPI.API_VERSION == 6);
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
		clickCard(ctx, "settings");
		waitForSettings(ctx);
		check("single-player: no server configs listed (local files are the server's)",
				settingsHas(ctx, s -> !s.listsConfig("example", true) && s.serverState().isEmpty()));
		closeMenu(ctx);
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
		clickCard(ctx, "settings");
		check("remote: Settings opens the settings editor on a server", waitForSettings(ctx));
		check("remote: non-op sees 'No permission'", waitUntil(ctx, () -> "No permission".equals(onSettings(ctx, SettingsScreen::serverState))));
		ctx.takeScreenshot("mejtacore-08-server-settings-denied");

		perms.set(edit, PermissionLevel.ALL);
		perms.save(); // a config change: the server pushes fresh catalogs, the open editor refreshes
		check("remote: granting mejtacore.config.edit lists the server's configs",
				waitUntil(ctx, () -> settingsHas(ctx, s -> s.listsConfig("example", true))));
		check("remote: permissions file hidden without mejtacore.config.permissions",
				settingsHas(ctx, s -> !s.listsConfig(PermissionRegistry.CONFIG_ID, true)));
		ctx.takeScreenshot("mejtacore-09-server-settings");

		clickSidebar(ctx, "example", true);
		check("remote: server config opens", settingsHas(ctx, s -> "example".equals(s.selectedConfigId()) && s.showsServerConfig()));
		typeSetting(ctx, "radius", "9");
		check("remote: typing changes the server's value (8 -> 9)", waitUntil(ctx, () -> example.get(ExampleAddon.RADIUS) == 9));
		check("remote: server saved the change to disk", waitUntil(ctx,
				() -> read(example.file()).contains("\"radius\": 9")));
		check("remote: the server's confirmation is shown in the editor", waitUntil(ctx, () -> statusContains(ctx, "Saved on the server")));
		clickSetting(ctx, "mode", SettingsScreen.Part.CONTROL);
		check("remote: enum setting cycles on the server (NORMAL -> FAST)",
				waitUntil(ctx, () -> example.get(ExampleAddon.MODE) == ExampleAddon.Mode.FAST));
		check("remote: editor shows the server's value", waitUntil(ctx, () -> "9".equals(onSettings(ctx, s -> s.shownValue("radius")))));
		ctx.takeScreenshot("mejtacore-10-server-config-edit");

		clearChat(ctx);
		ctx.runOnClient(mc -> {
			if (mc.screen instanceof SettingsScreen) {
				// Past the editor's own check, so the server's refusal is what the player sees.
				ClientPlayNetworking.send(new ConfigPayloads.EditConfig("example", "SET", "greeting", "   "));
			}
		});
		check("remote: a refused edit is shown in the editor", waitUntil(ctx, () -> statusContains(ctx, "Server refused the change")
				&& statusContains(ctx, "greeting")));
		check("remote: a refused edit is also written to chat", chatContains(ctx, "Server refused the change"));
		check("remote: the refused value was not applied", !example.get(ExampleAddon.GREETING).isBlank());
		closeMenu(ctx);
		closeMenu(ctx);
		localCopyChecks(ctx);

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
		vanillaConfigMenuChecks(ctx, server, perms, edit);

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

	/**
	 * 1.4.0: with an addon installed on both sides, the Configuration page lists this computer's copy of its config.
	 * Editing it used to change only the client's file ("edits don't stick" on the server). The client and the test
	 * server share one JVM here, so the values cannot tell the copies apart: the checks are on what the menu opens.
	 * Needs mejtacore.config.edit granted.
	 */
	private void localCopyChecks(ClientGameTestContext ctx) {
		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickCard(ctx, "addon:example");
		check("local copy: the addon page offers both copies", sectionIs(ctx, "addon:example")
				&& waitUntil(ctx, () -> menuHas(ctx, s -> s.cardIds().containsAll(List.of("settings", "local_settings")))));
		clickCard(ctx, "settings");
		check("local copy: Settings of a config the server also has opens the SERVER's copy", waitForSettings(ctx)
				&& waitUntil(ctx, () -> settingsHas(ctx, s -> "example".equals(s.selectedConfigId()) && s.showsServerConfig())));
		check("local copy: the editor lists both copies", settingsHas(ctx, s -> s.listsConfig("example", false)));
		ctx.takeScreenshot("mejtacore-12-local-config-redirect");
		closeMenu(ctx);
		waitForMenu(ctx, "MejtaCore");
		clickCard(ctx, "local_settings");
		check("local copy: This Computer's Copy opens this computer's copy", waitForSettings(ctx)
				&& settingsHas(ctx, s -> "example".equals(s.selectedConfigId()) && !s.showsServerConfig()));
		closeMenu(ctx);
		closeMenu(ctx);

		chat(ctx, "/mejta gui");
		waitForMenu(ctx, "MejtaCore");
		clickCard(ctx, "local_settings");
		check("local copy: This Computer opens this computer's MejtaCore settings on a server",
				waitForSettings(ctx) && settingsHas(ctx, s -> "mejtacore".equals(s.selectedConfigId()) && !s.showsServerConfig()));
		closeMenu(ctx);
		closeMenu(ctx);

		chat(ctx, "/mejtaadmin config");
		check("/mejtaadmin config opens the client's settings editor on the server's MejtaCore config", waitForSettings(ctx)
				&& waitUntil(ctx, () -> settingsHas(ctx, s -> "mejtacore".equals(s.selectedConfigId()) && s.showsServerConfig())));
		closeMenu(ctx);
	}

	/**
	 * 1.4.0: {@code /mejtaadmin config menu}, a server-side chest menu that needs no client mod. Runs as a non-op with
	 * mejtacore.config.edit granted; every change must reach the server's file through the normal edit path.
	 */
	private void vanillaConfigMenuChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, ConfigHandle perms,
			Setting<PermissionLevel> edit) {
		ConfigHandle example = ExampleAddon.config();
		Path file = example.file();
		UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
		example.set(ExampleAddon.RADIUS, 12);
		example.set(ExampleAddon.MODE, ExampleAddon.Mode.FAST);
		example.save();
		resendCommands(server);

		chat(ctx, "/mejtaadmin config menu");
		check("vanilla menu: /mejtaadmin config menu opens a plain chest", waitForContainer(ctx, "Server Settings"));
		check("vanilla menu: lists the example config", containerSlot(ctx, "Example Addon", true) >= 0);
		check("vanilla menu: permissions file hidden from non-owners", containerSlot(ctx, "Permissions", true) < 0);
		ctx.takeScreenshot("mejtacore-13-vanilla-config-menu");
		clickContainerSlot(ctx, containerSlot(ctx, "Example Addon", true), 0);
		check("vanilla menu: config page opens", waitForContainer(ctx, "Example Addon settings"));
		ctx.takeScreenshot("mejtacore-14-vanilla-config-settings");

		clickContainerSlot(ctx, containerSlot(ctx, "Enabled:", false), 0);
		check("vanilla menu: click toggles a boolean on the server and saves it", waitUntil(ctx, () -> !example.get(ExampleAddon.ENABLED)
				&& read(file).contains("\"enabled\": false")));
		check("vanilla menu: the icon shows the new value", waitUntil(ctx, () -> containerName(ctx, "Enabled:").endsWith("OFF")));

		clickContainerSlot(ctx, containerSlot(ctx, "Mode:", false), 0);
		check("vanilla menu: left click cycles an enum (FAST -> SILENT)",
				waitUntil(ctx, () -> example.get(ExampleAddon.MODE) == ExampleAddon.Mode.SILENT));
		clickContainerSlot(ctx, containerSlot(ctx, "Mode:", false), 1);
		check("vanilla menu: right click cycles back (SILENT -> FAST), saved", waitUntil(ctx,
				() -> example.get(ExampleAddon.MODE) == ExampleAddon.Mode.FAST && read(file).contains("\"mode\": \"FAST\"")));

		clickContainerSlot(ctx, containerSlot(ctx, "Radius:", false), 0);
		check("vanilla menu: left click adds 1 to a number (12 -> 13), saved", waitUntil(ctx,
				() -> example.get(ExampleAddon.RADIUS) == 13 && read(file).contains("\"radius\": 13")));
		clickContainerSlot(ctx, containerSlot(ctx, "Radius:", false), 1);
		check("vanilla menu: right click subtracts 1 (13 -> 12)", waitUntil(ctx, () -> example.get(ExampleAddon.RADIUS) == 12));
		clickContainerSlot(ctx, containerSlot(ctx, "Speed:", false), 0);
		check("vanilla menu: decimal step (1.0 -> 1.1)", waitUntil(ctx, () -> example.get(ExampleAddon.SPEED) == 1.1));
		hoverContainerSlot(ctx, containerSlot(ctx, "Speed:", false));
		ctx.getInput().pressKey(GLFW.GLFW_KEY_Q);
		check("vanilla menu: drop key resets to the default (1.1 -> 1.0)", waitUntil(ctx,
				() -> example.get(ExampleAddon.SPEED) == 1.0 && read(file).contains("\"speed\": 1.0")));
		check("vanilla menu: nothing dropped or carried", server.computeOnServer(s -> {
			ServerPlayer p = s.getPlayerList().getPlayer(me);
			return p.containerMenu.getCarried().isEmpty() && p.level().getEntitiesOfClass(
					net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(8)).isEmpty();
		}));
		check("vanilla menu: changes are audited and published", waitUntil(ctx, () -> !notificationLog(r ->
				r.get("channel").getAsString().equals("mejtacore.config")
						&& r.getAsJsonObject("fields").get("key").getAsString().equals("speed")).isEmpty()));

		int token = containerSlot(ctx, "Api Token:", false);
		check("vanilla menu: a secret shows only (not set)", token >= 0 && containerName(ctx, "Api Token:").endsWith("(not set)"));
		clearChat(ctx);
		clickContainerSlot(ctx, token, 0);
		check("vanilla menu: a secret cannot be edited from the menu", waitUntil(ctx, () -> chatHas(ctx, "cannot be changed from the menu"))
				&& example.get(ExampleAddon.API_TOKEN).isEmpty()
				&& ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?>));

		clearChat(ctx);
		clickContainerSlot(ctx, containerSlot(ctx, "Greeting:", false), 0);
		check("vanilla menu: a text setting closes the menu", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen == null)));
		check("vanilla menu: and offers the command in chat", chatContains(ctx, "Click here to type a new value for greeting"));


		chat(ctx, "/mejtaadmin config menu example");
		check("vanilla menu: opens one config directly", waitForContainer(ctx, "Example Addon settings"));
		perms.reset(edit);
		perms.save();
		clickContainerSlot(ctx, containerSlot(ctx, "Radius:", false), 0);
		check("vanilla menu: closes once the permission is revoked, without a change", waitUntil(ctx,
				() -> ctx.computeOnClient(mc -> !(mc.screen instanceof AbstractContainerScreen<?>))) && example.get(ExampleAddon.RADIUS) == 12);
		resendCommands(server);
		chat(ctx, "/mejtaadmin config menu");
		check("vanilla menu: refused without the permission", !waitForContainer(ctx, "Server Settings"));

		perms.set(edit, PermissionLevel.ALL);
		perms.save();
		example.set(ExampleAddon.ENABLED, true);
		example.save();
	}

	/** API 4: secret settings never reach clients, chat, the log or notifications. Needs mejtacore.config.edit granted. */
	private void secretSettingChecks(ClientGameTestContext ctx, ConfigHandle perms) {
		ConfigHandle example = ExampleAddon.config();
		String token = "tok-" + UUID.randomUUID();
		@SuppressWarnings("unchecked")
		Setting<PermissionLevel> secrets = (Setting<PermissionLevel>) perms.spec().setting("mejtacore.config.secrets").orElseThrow();

		chat(ctx, "/mejtacore config");
		waitForSettings(ctx);
		waitUntil(ctx, () -> settingsHas(ctx, s -> s.listsConfig("example", true)));
		ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).selectConfig("example", true));
		check("secret: not sent to the client's settings editor", settingsHas(ctx, s -> !s.listedKeys().contains("api_token")
				&& s.listedKeys().contains("radius")));
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

	// ---------------------------------------------------------------- API 4 (MejtaCore 1.3.0)

	private void api4Checks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		clearChat(ctx);
		chat(ctx, "/examplecmd");
		check("api4: top-level addon command runs", chatContains(ctx, "example root command ok"));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin example examplecmd");
		check("api4: top-level command mirrored under /mejtaadmin", chatContains(ctx, "example root command ok"));
		clearChat(ctx);
		chat(ctx, "/list");
		check("api4: vanilla command wins a name clash", chatContains(ctx, "players online") && !chatHas(ctx, "example list ok"));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin example list");
		check("api4: clashing command still reachable under /mejtaadmin", chatContains(ctx, "example list ok"));
		check("api4: clash reported in diagnostics", Diagnostics.entries().stream()
				.anyMatch(e -> e.message().contains("/list of addon 'example' is not registered")));

		dataStoreChecks(server);
		auditChecks(ctx);
		serverMenuChecks(ctx, server);
		presenceAndSchedulerChecks(ctx, server);
	}

	private void dataStoreChecks(TestDedicatedServerContext server) {
		DataStore store = MejtaCoreAPI.dataStore(ExampleAddon.ID);
		JsonObject doc = new JsonObject();
		doc.addProperty("value", "kept");
		boolean wrote;
		try {
			store.writeNow("test/item", doc);
			wrote = true;
		} catch (IOException e) {
			LOG.error("[MejtaTest] writeNow failed", e);
			wrote = false;
		}
		check("api4: data store writeNow", wrote);
		Path file = MejtaCoreAPI.dataDirectory(ExampleAddon.ID).resolve("store/test/item.json");
		check("api4: data store file on disk", read(file).contains("\"kept\""));
		check("api4: data store reads it back", store.read("test/item").map(o -> o.get("value").getAsString()).orElse("").equals("kept"));
		check("api4: data store lists the key", store.keys("test").contains("test/item"));
		check("api4: data store rejects path traversal", throwsIae(() -> store.read("../escape")));
		store.delete("test/item");
		await(store.flush());
		check("api4: data store delete", !Files.exists(file) && store.read("test/item").isEmpty());
	}

	private void auditChecks(ClientGameTestContext ctx) {
		clearChat(ctx);
		chat(ctx, "/mejtaadmin example audit");
		check("api4: audit command ran", chatContains(ctx, "audit recorded"));
		List<JsonObject> recent = await(AuditTrail.get().recent(20));
		String me = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		check("api4: audit entry has actor, action, target, outcome", recent != null && recent.stream().anyMatch(r ->
				r.get("action").getAsString().equals("example.test") && r.get("actor").getAsString().equals(me)
						&& r.get("actor_kind").getAsString().equals("PLAYER") && r.get("target").getAsString().equals("nothing")
						&& r.get("outcome").getAsString().equals("SUCCESS") && r.has("hash") && r.has("ts")));
		check("api4: config edits are audited", recent != null && recent.stream().anyMatch(r ->
				r.get("action").getAsString().equals("config.edit")));
		AuditChain.Verification verification = await(AuditTrail.get().verify());
		check("api4: audit chain intact", verification != null && verification.intact());
		check("api4: audit published as notification", waitUntil(ctx, () -> !notificationLog(r ->
				r.get("channel").getAsString().equals("example.audit")).isEmpty()));
	}

	private void serverMenuChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
		// Chests are the default, for this client too (1.9.0).
		server.runOnServer(s -> {
			ServerPlayer p = s.getPlayerList().getPlayer(me);
			p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
		});
		ctx.waitTicks(5);
		int clicksBefore = ExampleAddon.MENU_CLICKS.get();
		int closesBefore = ExampleAddon.MENU_CLOSES.get();
		chat(ctx, "/mejtaadmin example menu");
		check("api4: server menu opens on the client as a plain chest", waitUntil(ctx, () -> ctx.computeOnClient(mc ->
				mc.screen instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().equals("Example menu"))));
		ctx.takeScreenshot("mejtacore-11-server-menu");

		clickContainerSlot(ctx, 13, 0);
		check("api4: left click runs the button", waitUntil(ctx, () -> ExampleAddon.MENU_CLICKS.get() == clicksBefore + 1)
				&& ExampleAddon.lastClickType == MenuClick.Type.LEFT);
		check("api4: icon refreshed after click", waitUntil(ctx, () -> ctx.computeOnClient(mc ->
				mc.player.containerMenu.getSlot(13).getItem().getHoverName().getString().equals("Clicks: " + (clicksBefore + 1)))));
		check("api4: nothing on the cursor after clicking an icon", ctx.computeOnClient(mc -> mc.player.containerMenu.getCarried().isEmpty()));

		clickContainerSlot(ctx, 13, 1);
		check("api4: right click reported as RIGHT", waitUntil(ctx, () -> ExampleAddon.lastClickType == MenuClick.Type.RIGHT));

		hoverContainerSlot(ctx, 4);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_1);
		ctx.waitTicks(5);
		hoverContainerSlot(ctx, 13);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_Q);
		ctx.waitTicks(5);
		int playerHotbar0 = 3 * 9 + 27; // menu slots, then 27 main inventory slots, then the hotbar
		clickContainerSlot(ctx, playerHotbar0, 0);
		ctx.waitTicks(5);
		boolean inventoryIntact = server.computeOnServer(s -> {
			ServerPlayer p = s.getPlayerList().getPlayer(me);
			ItemStack hotbar = p.getInventory().getItem(0);
			return hotbar.is(Items.DIAMOND) && hotbar.getCount() == 3 && p.containerMenu.getCarried().isEmpty()
					&& p.getInventory().countItem(Items.PAPER) == 0 && p.getInventory().countItem(Items.EMERALD) == 0;
		});
		check("api4: number key, drop and own-inventory clicks move nothing (server side)", inventoryIntact);
		check("api4: no item entity dropped", server.computeOnServer(s -> s.getPlayerList().getPlayer(me).level()
				.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
						s.getPlayerList().getPlayer(me).getBoundingBox().inflate(8)).isEmpty()));
		check("api4: client view re-synced", waitUntil(ctx, () -> ctx.computeOnClient(mc ->
				mc.player.containerMenu.getSlot(4).getItem().is(Items.PAPER)
						&& mc.player.containerMenu.getCarried().isEmpty())));

		ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		check("api4: onClose runs once", waitUntil(ctx, () -> ExampleAddon.MENU_CLOSES.get() == closesBefore + 1));
		server.runOnServer(s -> s.getPlayerList().getPlayer(me).getInventory().setItem(0, ItemStack.EMPTY));
		pagedMenuChecks(ctx, server, me);

		ctx.runOnClient(mc -> MejtaCore.coreConfig().set(CoreSettings.SERVER_MENU_SCREENS, true));
		ctx.waitTicks(5);
		serverMenuScreenChecks(ctx, server);
	}

	/** 1.9.0: a paged list in a 2-row chest (9 entries a page, then the bar in slots 9 to 17). */
	private static void openPagedMenu(TestDedicatedServerContext server, UUID me) {
		server.runOnServer(s -> PagedMenu.<String>builder(Component.literal("Paged menu"),
						() -> java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(i -> "Entry " + i).toList())
				.rows(2)
				.entry(e -> MenuIcons.of(Items.PAPER, Component.literal(e)), null)
				.open(s.getPlayerList().getPlayer(me)));
	}

	private static String slotName(ClientGameTestContext ctx, int slot) {
		return ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(slot).getItem().getHoverName().getString());
	}

	private void pagedMenuChecks(ClientGameTestContext ctx, TestDedicatedServerContext server, UUID me) {
		openPagedMenu(server, me);
		check("1.9: a paged menu opens as a chest with the shared bar", waitUntil(ctx, () -> ctx.computeOnClient(mc ->
				mc.screen instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().equals("Paged menu")))
				&& slotName(ctx, 0).equals("Entry 1") && slotName(ctx, 13).equals("20 entries")
				&& slotName(ctx, 17).equals("Close") && slotName(ctx, 14).startsWith("Next page"));
		check("1.9: unused bar slots hold filler panes (no Back, no previous page on page 1)", ctx.computeOnClient(mc ->
				MenuIcons.isFiller(mc.player.containerMenu.getSlot(9).getItem()) && MenuIcons.isFiller(mc.player.containerMenu.getSlot(12).getItem())));
		int containerId = ctx.computeOnClient(mc -> mc.player.containerMenu.containerId);
		clickContainerSlot(ctx, 14, 0);
		check("1.9: next page turns in place", waitUntil(ctx, () -> slotName(ctx, 0).equals("Entry 10"))
				&& ctx.computeOnClient(mc -> mc.player.containerMenu.containerId) == containerId
				&& slotName(ctx, 12).startsWith("\u2190 Previous page"));
		clickContainerSlot(ctx, 14, 0);
		check("1.9: the last page shows the rest and no next page", waitUntil(ctx, () -> slotName(ctx, 0).equals("Entry 19"))
				&& ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(2).getItem().isEmpty()
						&& MenuIcons.isFiller(mc.player.containerMenu.getSlot(14).getItem())));
		clickContainerSlot(ctx, 17, 0);
		check("1.9: Close closes it", waitUntil(ctx, () -> ctx.computeOnClient(mc -> mc.screen == null)));
	}

	/** 1.7.0: a server menu opens as a MejtaCore screen on a client with MejtaCore. */
	private void serverMenuScreenChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
		int clicksBefore = ExampleAddon.MENU_CLICKS.get();
		int closesBefore = ExampleAddon.MENU_CLOSES.get();
		chat(ctx, "/mejtaadmin example menu");
		check("1.7: server menu opens as a MejtaCore screen", waitUntil(ctx, () -> ctx.computeOnClient(mc ->
				mc.screen instanceof ServerMenuScreen screen && screen.getTitle().getString().equals("Example menu"))));
		check("1.7: no container is opened on the server", server.computeOnServer(s -> {
			ServerPlayer p = s.getPlayerList().getPlayer(me);
			return p.containerMenu == p.inventoryMenu && ScreenMenus.openTitle(p).orElse("").equals("Example menu");
		}));
		check("1.7: icons are cards in chest order; a plain icon is not clickable", serverMenuHas(ctx, s ->
				s.cardIds().equals(List.of("slot_4", "slot_13")) && !s.card("slot_4").clickable() && s.card("slot_13").clickable()));
		ctx.takeScreenshot("mejtacore-13-server-menu-screen");

		clickCard(ctx, "slot_13");
		check("1.7: a click runs the button on the server", waitUntil(ctx, () -> ExampleAddon.MENU_CLICKS.get() == clicksBefore + 1)
				&& ExampleAddon.lastClickType == MenuClick.Type.LEFT);
		check("1.7: the server's refresh redraws the card", waitUntil(ctx, () -> serverMenuHas(ctx,
				s -> s.card("slot_13").title().getString().equals("Clicks: " + (clicksBefore + 1)))));
		ctx.getInput().holdShift();
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof ServerMenuScreen s ? s.cardPosition("slot_13") : null), 1);
		ctx.getInput().releaseShift();
		check("1.7: shift-right-click reaches the server as SHIFT_RIGHT", waitUntil(ctx, () -> ExampleAddon.lastClickType == MenuClick.Type.SHIFT_RIGHT));
		clickCard(ctx, "slot_4");
		ctx.waitTicks(5);
		check("1.7: clicking a plain icon does nothing", ExampleAddon.MENU_CLICKS.get() == clicksBefore + 2);

		closeMenu(ctx);
		check("1.7: closing the screen ends the menu and runs onClose once", waitUntil(ctx,
				() -> ExampleAddon.MENU_CLOSES.get() == closesBefore + 1)
				&& server.computeOnServer(s -> ScreenMenus.openTitle(s.getPlayerList().getPlayer(me)).isEmpty()));

		openPagedMenu(server, me);
		check("1.9: as a screen, filler panes are left out", waitUntil(ctx, () -> serverMenuHas(ctx, s -> s.cardIds().equals(List.of(
				"slot_0", "slot_1", "slot_2", "slot_3", "slot_4", "slot_5", "slot_6", "slot_7", "slot_8", "slot_13", "slot_14", "slot_17")))));
		closeMenu(ctx);
	}

	private void presenceAndSchedulerChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
		check("api4: visible by default", server.computeOnServer(s -> !MejtaCoreAPI.presence().isHidden(me)
				&& MejtaCoreAPI.presence().publiclyOnline(s).size() == 1));
		ExampleAddon.HIDDEN.add(me);
		check("api4: visibility provider hides a player", server.computeOnServer(s -> MejtaCoreAPI.presence().isHidden(me)
				&& MejtaCoreAPI.presence().publiclyOnline(s).isEmpty()
				&& MejtaCoreAPI.presence().canSee(s.getPlayerList().getPlayer(me), me)));
		ExampleAddon.HIDDEN.remove(me);

		java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicBoolean onServerThread = new java.util.concurrent.atomic.AtomicBoolean();
		server.runOnServer(s -> {
			MejtaCoreAPI.scheduler().runLater(5, () -> onServerThread.set(s.isSameThread()));
			Scheduler.Task task = MejtaCoreAPI.scheduler().runEvery(2, runs::incrementAndGet);
			MejtaCoreAPI.scheduler().runLater(11, task::cancel);
		});
		ctx.waitTicks(30);
		check("api4: scheduler runs later on the server thread", onServerThread.get());
		check("api4: repeating task ran about 5 times, then stopped (" + runs.get() + ")", runs.get() >= 4 && runs.get() <= 6);
	}

	private static boolean waitForSettings(ClientGameTestContext ctx) {
		boolean open = waitForScreenClass(ctx, SettingsScreen.class);
		ctx.waitTicks(2);
		return open;
	}

	/** {@code fn} applied to the open settings editor, on the client thread; {@code null} if none is open. */
	private static <T> T onSettings(ClientGameTestContext ctx, java.util.function.Function<SettingsScreen, T> fn) {
		return ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen screen ? fn.apply(screen) : null);
	}

	/** Whether the settings editor is open and {@code test} holds for it. */
	private static boolean settingsHas(ClientGameTestContext ctx, java.util.function.Predicate<SettingsScreen> test) {
		return ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen screen && test.test(screen));
	}

	/** Moves the real cursor to GUI position {@code pos} and presses a mouse button. */
	private static void clickAt(ClientGameTestContext ctx, int[] pos, int button) {
		if (pos == null) {
			LOG.error("[MejtaTest] cannot click a missing widget");
			return;
		}
		double factor = ctx.computeOnClient(mc -> (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth());
		ctx.getInput().setCursorPos(pos[0] * factor, pos[1] * factor);
		ctx.getInput().pressMouse(button);
		ctx.waitTicks(2);
	}

	private static void clickSetting(ClientGameTestContext ctx, String key, SettingsScreen.Part part) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s ? s.positionOf(key, part) : null), 0);
	}

	private static void clickSettingsButton(ClientGameTestContext ctx, String id) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s ? s.buttonPosition(id) : null), 0);
	}

	private static void clickSidebar(ClientGameTestContext ctx, String configId, boolean server) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s ? s.sidebarPosition(configId, server) : null), 0);
	}

	/** Clicks the setting's field, replaces its text and presses Enter. */
	private static void typeSetting(ClientGameTestContext ctx, String key, String text) {
		clickSetting(ctx, key, SettingsScreen.Part.FIELD);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_END);
		String shown = onSettings(ctx, s -> s.shownValue(key));
		for (int i = 0; shown != null && i < shown.length(); i++) {
			ctx.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}
		ctx.getInput().typeChars(text);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(3);
	}

	private static void typeSearch(ClientGameTestContext ctx, String text) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s ? s.searchPosition() : null), 0);
		ctx.getInput().typeChars(text);
		ctx.waitTicks(2);
	}

	private static void clearSearch(ClientGameTestContext ctx) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s ? s.searchPosition() : null), 0);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_END);
		for (int i = 0; i < 64; i++) {
			ctx.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
		}
		ctx.waitTicks(2);
	}

	private static boolean statusContains(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> MenuStatus.current() != null && MenuStatus.current().getString().contains(text));
	}

	/** Waits (up to 40 ticks) for a vanilla container screen with this title, e.g. a server menu. */
	private static boolean waitForContainer(ClientGameTestContext ctx, String title) {
		for (int i = 0; i < 40; i++) {
			if (ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().equals(title))) {
				ctx.waitTicks(2);
				return true;
			}
			ctx.waitTick();
		}
		return false;
	}

	/** The first menu slot (of a 6-row server menu) whose item name equals, or starts with, {@code name}. */
	private static int containerSlot(ClientGameTestContext ctx, String name, boolean exact) {
		return ctx.computeOnClient(mc -> {
			if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
				return -1;
			}
			int slots = Math.min(54, screen.getMenu().slots.size());
			for (int i = 0; i < slots; i++) {
				ItemStack stack = screen.getMenu().getSlot(i).getItem();
				String shown = stack.isEmpty() ? "" : stack.getHoverName().getString();
				if (exact ? shown.equals(name) : shown.startsWith(name)) {
					return i;
				}
			}
			return -1;
		});
	}

	private static String containerName(ClientGameTestContext ctx, String prefix) {
		int slot = containerSlot(ctx, prefix, false);
		return slot < 0 ? "" : ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(slot).getItem().getHoverName().getString());
	}

	private static void clickContainerSlot(ClientGameTestContext ctx, int slot, int button) {
		if (slot < 0) {
			LOG.error("[MejtaTest] cannot click missing container slot");
			return;
		}
		hoverContainerSlot(ctx, slot);
		ctx.getInput().pressMouse(button);
		ctx.waitTicks(3);
	}

	/** Moves the mouse over a slot of a vanilla container screen. */
	private static void hoverContainerSlot(ClientGameTestContext ctx, int slot) {
		if (slot < 0 || !ctx.computeOnClient(mc -> mc.screen instanceof AbstractContainerScreen<?>)) {
			LOG.error("[MejtaTest] cannot hover container slot {}", slot);
			return;
		}
		double[] pos = ctx.computeOnClient(mc -> {
			AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
			int left = intField(screen, "leftPos");
			int top = intField(screen, "topPos");
			var s = screen.getMenu().getSlot(slot);
			double factor = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			return new double[] {(left + s.x + 8) * factor, (top + s.y + 8) * factor};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTicks(1);
	}

	private static int intField(Object target, String name) {
		try {
			Field field = AbstractContainerScreen.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.getInt(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot read " + name, e);
		}
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
		// Chat only opens in game, so close whatever screens (and the menus they return to) are still open.
		for (int i = 0; i < 5 && ctx.computeOnClient(mc -> mc.screen != null); i++) {
			closeMenu(ctx);
		}
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(command);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(3);
	}

	/** Waits (up to 40 ticks) for a MejtaCore menu screen with this title. */
	private static boolean waitForMenu(ClientGameTestContext ctx, String title) {
		try {
			ctx.waitFor(mc -> mc.screen instanceof CardScreen && mc.screen.getTitle().getString().equals(title), 40);
			ctx.waitTicks(1);
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

	/** Whether a MejtaCore menu is open and {@code test} holds for it. */
	private static boolean menuHas(ClientGameTestContext ctx, java.util.function.Predicate<CardScreen> test) {
		return ctx.computeOnClient(mc -> mc.screen instanceof CardScreen screen && test.test(screen));
	}

	private static boolean serverMenuHas(ClientGameTestContext ctx, java.util.function.Predicate<ServerMenuScreen> test) {
		return ctx.computeOnClient(mc -> mc.screen instanceof ServerMenuScreen screen && test.test(screen));
	}

	/** Whether the main menu is open on {@code section}. */
	private static boolean sectionIs(ClientGameTestContext ctx, String section) {
		return ctx.computeOnClient(mc -> mc.screen instanceof MainMenuScreen menu && menu.section().equals(section));
	}

	private static void clickCard(ClientGameTestContext ctx, String id) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof CardScreen s ? s.cardPosition(id) : null), 0);
	}

	private static void clickSidebarEntry(ClientGameTestContext ctx, String id) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof CardScreen s ? s.sidebarPosition(id) : null), 0);
	}

	private static void clickFooter(ClientGameTestContext ctx, String id) {
		clickAt(ctx, ctx.computeOnClient(mc -> mc.screen instanceof CardScreen s ? s.footerPosition(id) : null), 0);
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
