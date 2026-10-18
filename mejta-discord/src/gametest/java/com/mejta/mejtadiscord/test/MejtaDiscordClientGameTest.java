package com.mejta.mejtadiscord.test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.addon.AddonState;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtadiscord.DiscordPermissions;
import com.mejta.mejtadiscord.DiscordSettings;
import com.mejta.mejtadiscord.MejtaDiscord;
import com.mejta.mejtadiscord.text.Inbound;

/**
 * End-to-end tests of MejtaDiscord without Discord: with nothing configured it must stay harmless;
 * secrets never land in the normal config file; Discord messages reach Minecraft chat as plain,
 * rate-limited text; only routed notifications are accepted. Real Discord delivery is checked by hand
 * (see the README). Every check logs {@code [MejtaDiscordTest] PASS/FAIL}.
 */
public final class MejtaDiscordClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MejtaDiscordTest");
	/** Made up and never sent to; assembled at runtime so secret scanners do not mistake it for a leak. */
	private static final String FAKE_WEBHOOK = "https://discord.com/api/" + "webhooks/123456789012345678/" + "TESTONLYabcdefghijklmnopqrstuvwxyz0123";

	private final List<String> failures = new ArrayList<>();
	private int passed;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		check("mejtadiscord addon active", MejtaCoreAPI.getAddon(MejtaDiscord.ID).map(a -> a.state() == AddonState.ACTIVE).orElse(false));
		check("notification provider registered", MejtaCoreAPI.notifications().providerIds().contains(MejtaDiscord.ID));
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer()) {
			TestServerConnection connection = server.connect();
			connection.getClientLevel().waitForChunksRender();
			try {
				chat(ctx, "/mejtaadmin mejtadiscord status");
				check("unauthorized: status is not available to a non-operator", refused(ctx));
				grant(server, true);
				unconfiguredChecks(ctx, server);
				secretChecks(ctx, server);
				inboundChecks(ctx, server);
				routingChecks();
			} finally {
				grant(server, false);
				connection.close();
			}
		}
		LOG.info("[MejtaDiscordTest] SUMMARY: {} passed, {} failed", passed, failures.size());
		if (!failures.isEmpty()) {
			throw new AssertionError("MejtaDiscord tests failed: " + String.join("; ", failures));
		}
	}

	private void unconfiguredChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtadiscord status");
		check("status: unset webhooks are reported", chatContains(ctx, "Webhook 'default': not set"));
		check("status: missing bot settings are named", chatContains(ctx, "needs bot.token and chat.to_minecraft.channel_id"));
		chat(ctx, "/mejtaadmin mejtadiscord test default");
		check("test: refused while the webhook is not set", chatContains(ctx, "is not set, disabled or busy"));
		chat(ctx, "hello from minecraft");
		check("chat still works with nothing configured", chatContains(ctx, "hello from minecraft"));
		chat(ctx, "/mejtaadmin mejtadiscord reconnect");
		check("reconnect: refused while the relay is not set up", chatContains(ctx, "not set up"));
	}

	private void secretChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		ConfigHandle config = config();
		server.runCommand("mejtaadmin config set " + MejtaDiscord.ID + " webhook.default.url https://evil.example/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz");
		check("secret: non-Discord webhook URL rejected", server.computeOnServer(s -> config.get(DiscordSettings.WEBHOOK_DEFAULT).isEmpty()));
		server.runCommand("mejtaadmin config set " + MejtaDiscord.ID + " webhook.default.url " + FAKE_WEBHOOK);
		check("secret: Discord webhook URL accepted from the console",
				waitUntil(ctx, () -> server.computeOnServer(s -> config.get(DiscordSettings.WEBHOOK_DEFAULT).equals(FAKE_WEBHOOK))));
		server.runOnServer(s -> config.save());
		check("secret: not in the normal config file", !fileContains(config.file(), "TESTONLY"));
		check("secret: kept in the secrets file", fileContains(config.file().getParent().resolve("secrets").resolve(MejtaDiscord.ID + ".json"), "TESTONLY"));
		clearChat(ctx);
		chat(ctx, "/mejtaadmin mejtadiscord status");
		check("status: webhook client created after the change", chatContains(ctx, "Webhook 'default': idle"));
		check("status: never shows the URL", !chatHas(ctx, "TESTONLY") && !chatHas(ctx, "discord.com/api"));
		server.runOnServer(s -> {
			config.reset(DiscordSettings.WEBHOOK_DEFAULT);
			config.save();
		});
		check("secret: reset", server.computeOnServer(s -> config.get(DiscordSettings.WEBHOOK_DEFAULT).isEmpty()));
	}

	private void inboundChecks(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		clearChat(ctx);
		relay(new Inbound("777777777777", "Test§cer", "§chello @everyone\nsecond line"));
		check("inbound: shown in Minecraft with the configured format", chatContains(ctx, "[Discord] Tester: hello @everyone second line"));
		check("inbound: no formatting codes reach the client", !ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(l -> l.contains("§"))));

		clearChat(ctx);
		int limit = config().get(DiscordSettings.CHAT_RATE);
		for (int i = 0; i < limit + 3; i++) {
			relay(new Inbound("888888888888", "Spammer", "spam " + i));
		}
		ctx.waitTicks(10);
		long shown = ctx.computeOnClient(mc -> chatLines(mc).stream().filter(l -> l.contains("Spammer: spam")).count());
		check("inbound: per-user rate limit (" + shown + " of " + (limit + 3) + " shown)", shown == limit);
		relay(new Inbound("999999999998", "Other", "not limited"));
		check("inbound: the limit is per user", chatContains(ctx, "Other: not limited"));

		setBool(DiscordSettings.CHAT_TO_MINECRAFT, false);
		relay(new Inbound("777777777777", "Tester", "while off"));
		ctx.waitTicks(10);
		check("inbound: nothing shown while chat.to_minecraft.enabled is off", !chatHas(ctx, "while off"));
		config().reset(DiscordSettings.CHAT_TO_MINECRAFT);
		config().save();
	}

	private void routingChecks() {
		NotificationProvider provider = provider();
		check("routing: anti-cheat alerts accepted by default",
				provider.accepts(Notification.builder("mejtaac", "mejtaac.alert").severity(Severity.WARNING).title("t").build()));
		check("routing: unrouted channels not accepted",
				!provider.accepts(Notification.builder("mejtautils", "mejtautils.freeze").severity(Severity.WARNING).title("t").build()));
		check("routing: MejtaDiscord's own warnings never go to Discord",
				!provider.accepts(Notification.builder(MejtaDiscord.ID, MejtaDiscord.CHANNEL_STATUS).severity(Severity.CRITICAL).title("t").build()));
		config().set(DiscordSettings.NOTIFY_MIN_SEVERITY, Severity.CRITICAL);
		config().save();
		check("routing: below notify.min_severity not accepted",
				!provider.accepts(Notification.builder("mejtaac", "mejtaac.alert").severity(Severity.WARNING).title("t").build()));
		config().reset(DiscordSettings.NOTIFY_MIN_SEVERITY);
		config().save();
		check("routing: webhook names are limited to default, alerts and staff",
				!config().trySet(DiscordSettings.NOTIFY_ROUTING, "mejtaac.*=somewhere"));
	}

	// ---------------------------------------------------------------- helpers

	private static ConfigHandle config() {
		return MejtaCore.configs().find(MejtaDiscord.ID).orElseThrow();
	}

	private static void setBool(Setting<Boolean> setting, boolean value) {
		config().set(setting, value);
		config().save();
	}

	/** Hands a message to the relay as the gateway would. */
	private static void relay(Inbound message) {
		try {
			Field field = MejtaDiscord.class.getDeclaredField("RELAY");
			field.setAccessible(true);
			Object relay = field.get(null);
			Method method = relay.getClass().getDeclaredMethod("toMinecraft", Inbound.class);
			method.setAccessible(true);
			method.invoke(relay, message);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static NotificationProvider provider() {
		try {
			Field field = MejtaDiscord.class.getDeclaredField("NOTIFICATIONS");
			field.setAccessible(true);
			return (NotificationProvider) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean fileContains(Path file, String text) {
		try {
			return Files.exists(file) && Files.readString(file).contains(text);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static void grant(TestDedicatedServerContext server, boolean on) {
		ConfigHandle perms = MejtaCore.configs().find("mejtacore.permissions").orElseThrow();
		for (MejtaPermission permission : DiscordPermissions.ALL) {
			Setting<PermissionLevel> setting = (Setting<PermissionLevel>) perms.spec().setting(permission.node()).orElseThrow();
			if (on) {
				perms.set(setting, PermissionLevel.ALL);
			} else {
				perms.reset(setting);
			}
		}
		perms.save();
		server.runOnServer(s -> {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				s.getCommands().sendCommands(p);
			}
		});
	}

	private static void chat(ClientGameTestContext ctx, String text) {
		ctx.getInput().pressKey(options -> options.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars(text);
		ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		ctx.waitTicks(5);
	}

	private static void clearChat(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.gui.getChat().clearMessages(false));
	}

	private static boolean chatHas(ClientGameTestContext ctx, String text) {
		return ctx.computeOnClient(mc -> chatLines(mc).stream().anyMatch(line -> line.contains(text)));
	}

	/** Minecraft's answer to a command the player may not use: unknown at the top level, an incorrect argument below it. */
	private static boolean refused(ClientGameTestContext ctx) {
		boolean found = waitUntil(ctx, () -> chatHas(ctx, "Unknown or incomplete command") || chatHas(ctx, "Incorrect argument for command"));
		if (!found) {
			LOG.error("[MejtaDiscordTest] the command was not refused. Chat: {}", ctx.computeOnClient(MejtaDiscordClientGameTest::chatLines));
		}
		return found;
	}

	private static boolean chatContains(ClientGameTestContext ctx, String text) {
		boolean found = waitUntil(ctx, () -> chatHas(ctx, text));
		if (!found) {
			LOG.error("[MejtaDiscordTest] chat did not contain '{}'. Chat: {}", text, ctx.computeOnClient(MejtaDiscordClientGameTest::chatLines));
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

	private static boolean waitUntil(ClientGameTestContext ctx, BooleanSupplier condition) {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean()) {
				return true;
			}
			ctx.waitTick();
		}
		return condition.getAsBoolean();
	}

	private void check(String name, boolean ok) {
		if (ok) {
			passed++;
			LOG.info("[MejtaDiscordTest] PASS {}", name);
		} else {
			failures.add(name);
			LOG.error("[MejtaDiscordTest] FAIL {}", name);
		}
	}
}
