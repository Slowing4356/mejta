package com.mejta.mejtacore.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.ConfigNames;
import com.mejta.mejtacore.config.SettingSteps;
import com.mejta.mejtacore.config.SettingText;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.network.ConfigPayloads.EditConfig;
import com.mejta.mejtacore.network.ConfigPayloads.EditResult;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteConfig;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteSetting;
import com.mejta.mejtacore.network.ConfigPayloads.RequestConfigs;
import com.mejta.mejtacore.network.ConfigPayloads.ServerConfigs;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/**
 * Lets players edit the server's configs from their MejtaCore menu (and from {@code /mejtaadmin
 * config}). The server is authoritative: every edit is permission-checked, validated with the real
 * setting (including addon validators), saved, and logged with the player's name. Clients without
 * MejtaCore are never sent anything.
 */
public final class ServerConfigSync {
	/** Notification channel for config changes made in-game or by command. */
	public static final String CHANNEL_CONFIG = "mejtacore.config";

	private static final int MAX_EDITS_PER_SECOND = 20;

	private static final Map<UUID, long[]> editWindows = new ConcurrentHashMap<>();
	/** Refused edits from players without any edit permission are audited at most once a minute each. */
	private static final long DENIED_AUDIT_INTERVAL_MS = 60_000;
	private static final Map<UUID, Long> lastDeniedAudit = new ConcurrentHashMap<>();
	private static final java.util.regex.Pattern CONFIG_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]{1,64}");
	private static volatile boolean dirty;

	private ServerConfigSync() {
	}

	/** Common init: payload types and server-side handlers. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ServerConfigs.TYPE, ServerConfigs.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditResult.TYPE, EditResult.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestConfigs.TYPE, RequestConfigs.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditConfig.TYPE, EditConfig.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(RequestConfigs.TYPE,
				(payload, ctx) -> ctx.server().execute(() -> sendTo(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(EditConfig.TYPE,
				(payload, ctx) -> ctx.server().execute(() -> handleEdit(ctx.player(), payload)));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sendTo(handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			editWindows.remove(handler.player.getUUID());
			lastDeniedAudit.remove(handler.player.getUUID());
		});
		ServerTickEvents.END_SERVER_TICK.register(ServerConfigSync::onTick);
	}

	/** Resend catalogs to everyone after any of these configs changes (edits, resets, reloads). */
	public static void watch(Collection<ConfigHandle> configs) {
		for (ConfigHandle config : configs) {
			config.addChangeListener(() -> dirty = true);
		}
	}

	private static void onTick(MinecraftServer server) {
		if (!dirty) {
			return;
		}
		dirty = false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendTo(player);
		}
	}

	/** Configs a command source or player may edit. */
	public static List<ConfigHandle> editableBy(PermissionSet permissions) {
		if (!PermissionRegistry.check(permissions, PermissionRegistry.CONFIG_EDIT)) {
			return List.of();
		}
		boolean permissionsFile = PermissionRegistry.check(permissions, PermissionRegistry.CONFIG_PERMISSIONS);
		List<ConfigHandle> out = new ArrayList<>();
		for (ConfigHandle handle : MejtaCore.configs().all()) {
			if (permissionsFile || !handle.id().equals(PermissionRegistry.CONFIG_ID)) {
				out.add(handle);
			}
		}
		return out;
	}

	public static Optional<ConfigHandle> editable(PermissionSet permissions, String configId) {
		return editableBy(permissions).stream().filter(h -> h.id().equals(configId)).findFirst();
	}

	private static void sendTo(ServerPlayer player) {
		if (player.connection == null || !ServerPlayNetworking.canSend(player, ServerConfigs.TYPE)) {
			return;
		}
		boolean permitted = PermissionRegistry.check(player.permissions(), PermissionRegistry.CONFIG_EDIT);
		List<RemoteConfig> configs = new ArrayList<>();
		for (ConfigHandle handle : editableBy(player.permissions())) {
			configs.add(describe(handle));
		}
		ServerPlayNetworking.send(player, new ServerConfigs(ConfigPayloads.PROTOCOL, permitted, configs));
	}

	private static RemoteConfig describe(ConfigHandle handle) {
		List<RemoteSetting> settings = new ArrayList<>();
		for (Setting<?> setting : handle.spec().settings()) {
			if (SettingText.isSensitive(setting)) {
				continue; // secrets never leave the server, not even masked
			}
			String defaultText = SettingText.format(setting, setting.defaultValue());
			String valueText = SettingText.format(setting, handle.get(setting));
			if (defaultText.length() > ConfigPayloads.MAX_TEXT || valueText.length() > ConfigPayloads.MAX_TEXT) {
				continue; // too long for the menu's payload; still editable with /mejtaadmin config set
			}
			String description = setting.description().length() > ConfigPayloads.MAX_TEXT
					? setting.description().substring(0, ConfigPayloads.MAX_TEXT - 3) + "..." : setting.description();
			boolean ranged = hasRealRange(setting);
			settings.add(new RemoteSetting(setting.key(), setting.type().name(), setting.displayName(), description,
					defaultText, valueText,
					ranged ? String.valueOf(setting.min()) : "", ranged ? String.valueOf(setting.max()) : "", setting.options()));
		}
		return new RemoteConfig(handle.id(), ConfigNames.displayName(handle), settings);
	}

	private static boolean hasRealRange(Setting<?> setting) {
		return SettingSteps.hasRealRange(setting);
	}

	/**
	 * An edit by a player from a menu: the client's Server Settings (network) or the server's own chest menu
	 * ({@code /mejtaadmin config menu}). Permission check, rate limit, then {@link #apply}: validation, save,
	 * audit and notification. Secret settings are refused, as for every menu.
	 */
	public static ConfigEditing.Result applyAsPlayer(ServerPlayer player, String configId, String op, String key, String value) {
		if (!PermissionRegistry.check(player.permissions(), PermissionRegistry.CONFIG_EDIT)) {
			// The menus never offer this without the permission: only a modified client (or a permission revoked a
			// moment ago) gets here. Answer it, but do not let it flood the audit log, the server log or providers.
			long now = System.currentTimeMillis();
			Long last = lastDeniedAudit.get(player.getUUID());
			if (last == null || now - last >= DENIED_AUDIT_INTERVAL_MS) {
				lastDeniedAudit.put(player.getUUID(), now);
				audit(MejtaCoreAPI.actor(player), configId, op, key, AuditOutcome.DENIED);
			}
			return new ConfigEditing.Result(false, "You may not edit server settings (needs " + PermissionRegistry.CONFIG_EDIT.node() + ")");
		}
		if (!withinRate(player.getUUID())) {
			return new ConfigEditing.Result(false, "Too many changes at once; slow down");
		}
		return apply(player.permissions(), MejtaCoreAPI.actor(player), configId, op, key, value, false);
	}

	private static void handleEdit(ServerPlayer player, EditConfig edit) {
		ConfigEditing.Result result = applyAsPlayer(player, edit.configId(), edit.op(), edit.key(), edit.value());
		if (ServerPlayNetworking.canSend(player, EditResult.TYPE)) {
			String message = result.message().length() > 1000 ? result.message().substring(0, 997) + "..." : result.message();
			ServerPlayNetworking.send(player, new EditResult(result.ok(), message));
		}
		sendTo(player); // the authoritative state, also after a rejected edit
	}

	/**
	 * Permission check, edit, save and audit log. Used by the network handler and by commands.
	 *
	 * @param byCommand {@code true} for {@code /mejtaadmin config}: sensitive settings may then be changed
	 *                  with {@code mejtacore.config.secrets}. Client menus never can.
	 */
	public static ConfigEditing.Result apply(PermissionSet permissions, AuditActor actor, String configId, String opName, String key,
			String value, boolean byCommand) {
		String who = actor.name();
		Optional<ConfigHandle> handle = editable(permissions, configId);
		if (handle.isEmpty()) {
			audit(actor, configId, opName, key, AuditOutcome.DENIED);
			return new ConfigEditing.Result(false, "You may not edit config '" + clean(configId, CONFIG_ID) + "'");
		}
		ConfigEditing.Op op;
		try {
			op = ConfigEditing.Op.valueOf(opName);
		} catch (IllegalArgumentException e) {
			return new ConfigEditing.Result(false, "Unknown operation " + clean(opName, OPERATION));
		}
		boolean mayChangeSecrets = byCommand && PermissionRegistry.check(permissions, PermissionRegistry.CONFIG_SECRETS);
		Optional<Setting<?>> target = key == null ? Optional.empty() : handle.get().spec().setting(key);
		if (op != ConfigEditing.Op.RESET_ALL && byCommand && !mayChangeSecrets
				&& target.map(SettingText::isSensitive).orElse(false)) {
			audit(actor, configId, opName, key, AuditOutcome.DENIED);
			return new ConfigEditing.Result(false, key + " is a secret setting; changing it needs "
					+ PermissionRegistry.CONFIG_SECRETS.node());
		}
		ConfigEditing.Result result = ConfigEditing.apply(handle.get(), op, key, value, mayChangeSecrets);
		if (result.ok()) {
			// The message never contains a sensitive value (see ConfigEditing).
			MejtaLog.CORE.info("[config] {} changed {}", who, result.message());
			publishChange(who, configId, opName, key, result.message(), Severity.INFO);
			audit(actor, configId, opName, key, AuditOutcome.SUCCESS);
		} else if (result.unsaved()) {
			// In effect until the next restart or reload, so it is announced, but as a problem.
			MejtaLog.CORE.warn("[config] {} changed {}", who, result.message());
			publishChange(who, configId, opName, key, result.message(), Severity.WARNING);
			audit(actor, configId, opName, key, AuditOutcome.FAILED);
		}
		return result;
	}

	/**
	 * Audit entries name the key, never a value. The ID, operation and key may come from a client, so anything that is
	 * not a well-formed name is recorded as {@code (invalid)}: no newlines or long text reach the logs.
	 */
	private static void audit(AuditActor actor, String configId, String op, String key, AuditOutcome outcome) {
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCore.MOD_ID, "config.edit")
				.actor(actor)
				.target("config " + clean(configId, CONFIG_ID))
				.outcome(outcome)
				.detail("operation", clean(op, OPERATION))
				.detail("key", key == null || key.isEmpty() ? "" : clean(key, Setting.KEY_PATTERN))
				.build());
	}

	private static final java.util.regex.Pattern OPERATION = java.util.regex.Pattern.compile("[A-Z_]{1,16}");

	static String clean(String text, java.util.regex.Pattern allowed) {
		return text != null && text.length() <= 64 && allowed.matcher(text).matches() ? text : "(invalid)";
	}

	/** Whether {@code key} of {@code configId} holds a secret, so command feedback must not be broadcast. */
	public static boolean isSensitive(String configId, String key) {
		if (SettingText.isSensitiveKey(key)) {
			return true;
		}
		return MejtaCore.configs().find(configId)
				.flatMap(h -> h.spec().setting(key))
				.map(SettingText::isSensitive)
				.orElse(false);
	}

	/**
	 * Publishes a config change on {@code mejtacore.config}, so it reaches staff chat, the notification
	 * file log and external providers such as MejtaDiscord. Values of secret settings are never included.
	 */
	private static void publishChange(String who, String configId, String op, String key, String change, Severity severity) {
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaCore.MOD_ID, CHANNEL_CONFIG)
				.severity(severity)
				.title(who + " changed " + configId)
				.message(change)
				.field("by", who)
				.field("config", configId)
				.field("operation", op)
				.field("key", key == null ? "" : key)
				.inGameAudience(PermissionRegistry.CONFIG_EDIT)
				.build());
	}

	public static ConfigEditing.Result apply(CommandSourceStack source, String configId, String op, String key, String value) {
		return apply(source.permissions(), MejtaCoreAPI.actor(source), configId, op, key, value, true);
	}

	private static boolean withinRate(UUID player) {
		long[] window = editWindows.computeIfAbsent(player, k -> new long[2]);
		long second = System.currentTimeMillis() / 1000;
		synchronized (window) {
			if (window[0] != second) {
				window[0] = second;
				window[1] = 0;
			}
			return ++window[1] <= MAX_EDITS_PER_SECOND;
		}
	}
}
