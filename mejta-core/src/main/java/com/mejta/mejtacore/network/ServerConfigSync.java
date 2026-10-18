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
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.config.SettingType;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtacore.config.ConfigEditing;
import com.mejta.mejtacore.config.ConfigNames;
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
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> editWindows.remove(handler.player.getUUID()));
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
			boolean ranged = hasRealRange(setting);
			settings.add(new RemoteSetting(setting.key(), setting.type().name(), setting.displayName(), setting.description(),
					SettingText.format(setting, setting.defaultValue()), SettingText.format(setting, handle.get(setting)),
					ranged ? String.valueOf(setting.min()) : "", ranged ? String.valueOf(setting.max()) : "", setting.options()));
		}
		return new RemoteConfig(handle.id(), ConfigNames.displayName(handle), settings);
	}

	private static boolean hasRealRange(Setting<?> setting) {
		if (setting.type() == SettingType.INTEGER) {
			return setting.min().intValue() != Integer.MIN_VALUE || setting.max().intValue() != Integer.MAX_VALUE;
		}
		if (setting.type() == SettingType.DECIMAL) {
			return setting.min().doubleValue() != -Double.MAX_VALUE || setting.max().doubleValue() != Double.MAX_VALUE;
		}
		return false;
	}

	private static void handleEdit(ServerPlayer player, EditConfig edit) {
		ConfigEditing.Result result;
		if (!withinRate(player.getUUID())) {
			result = new ConfigEditing.Result(false, "Too many changes at once; slow down");
		} else {
			result = apply(player.permissions(), player.getGameProfile().name(), edit.configId(), edit.op(), edit.key(), edit.value(),
					false);
		}
		if (ServerPlayNetworking.canSend(player, EditResult.TYPE)) {
			ServerPlayNetworking.send(player, new EditResult(result.ok(), result.message()));
		}
		sendTo(player); // the authoritative state, also after a rejected edit
	}

	/**
	 * Permission check, edit, save and audit log. Used by the network handler and by commands.
	 *
	 * @param byCommand {@code true} for {@code /mejtaadmin config}: sensitive settings may then be changed
	 *                  with {@code mejtacore.config.secrets}. Client menus never can.
	 */
	public static ConfigEditing.Result apply(PermissionSet permissions, String who, String configId, String opName, String key,
			String value, boolean byCommand) {
		Optional<ConfigHandle> handle = editable(permissions, configId);
		if (handle.isEmpty()) {
			return new ConfigEditing.Result(false, "You may not edit config '" + configId + "'");
		}
		ConfigEditing.Op op;
		try {
			op = ConfigEditing.Op.valueOf(opName);
		} catch (IllegalArgumentException e) {
			return new ConfigEditing.Result(false, "Unknown operation " + opName);
		}
		boolean mayChangeSecrets = byCommand && PermissionRegistry.check(permissions, PermissionRegistry.CONFIG_SECRETS);
		Optional<Setting<?>> target = key == null ? Optional.empty() : handle.get().spec().setting(key);
		if (op != ConfigEditing.Op.RESET_ALL && byCommand && !mayChangeSecrets
				&& target.map(SettingText::isSensitive).orElse(false)) {
			return new ConfigEditing.Result(false, key + " is a secret setting; changing it needs "
					+ PermissionRegistry.CONFIG_SECRETS.node());
		}
		ConfigEditing.Result result = ConfigEditing.apply(handle.get(), op, key, value, mayChangeSecrets);
		if (result.ok()) {
			// The message never contains a sensitive value (see ConfigEditing).
			MejtaLog.CORE.info("[config] {} changed {}", who, result.message());
			publishChange(who, configId, opName, key, result.message());
		}
		return result;
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
	private static void publishChange(String who, String configId, String op, String key, String change) {
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaCore.MOD_ID, CHANNEL_CONFIG)
				.severity(Severity.INFO)
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
		return apply(source.permissions(), source.getTextName(), configId, op, key, value, true);
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
