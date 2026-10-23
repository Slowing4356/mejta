package com.mejta.mejtacore.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.config.PendingEdits;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.network.ConfigPayloads.EditResult;
import com.mejta.mejtacore.network.ConfigPayloads.OpenSettings;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteConfig;
import com.mejta.mejtacore.network.ConfigPayloads.RequestConfigs;
import com.mejta.mejtacore.network.ConfigPayloads.ServerConfigs;
import com.mejta.mejtacore.ui.CardScreen;
import com.mejta.mejtacore.ui.MenuStatus;
import com.mejta.mejtacore.ui.ScreenOpener;
import com.mejta.mejtacore.ui.SettingsScreen;

/**
 * Client-side copy of the configs the current server lets this player edit. Empty in single-player
 * (where the settings editor edits the local files directly) and on servers without MejtaCore 1.2+.
 */
public final class RemoteConfigs {
	private static final Map<String, RemoteConfigHandle> handles = new LinkedHashMap<>();
	/** Edits sent and not answered yet; their values win over catalogs that were sent before them. */
	static final PendingEdits PENDING = new PendingEdits();
	private static boolean received;
	private static boolean permitted;
	/** The server's config protocol if it differs from this client's, else 0. */
	private static int incompatibleProtocol;

	private RemoteConfigs() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ServerConfigs.TYPE, (payload, ctx) -> ctx.client().execute(() -> apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(EditResult.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			PENDING.answered();
			showResult(ctx.client(), payload.ok(), payload.message());
		}));
		// /mejtaadmin config on the server: open the settings editor on the server's copy of the config.
		ClientPlayNetworking.registerGlobalReceiver(OpenSettings.TYPE, (payload, ctx) -> ctx.client().execute(() -> ScreenOpener.open(
				() -> new SettingsScreen(null, payload.configId().isEmpty() ? null : payload.configId(), true))));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(RemoteConfigs::clear));
	}

	/** Whether the current server offers remote config editing (not in single-player). */
	public static boolean supported() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.hasSingleplayerServer()) {
			return false;
		}
		try {
			return ClientPlayNetworking.canSend(RequestConfigs.TYPE);
		} catch (IllegalStateException | IllegalArgumentException e) {
			return false;
		}
	}

	public static void request() {
		if (supported()) {
			ClientPlayNetworking.send(new RequestConfigs());
		}
	}

	public static synchronized boolean received() {
		return received;
	}

	/** Whether the server lets this player edit its configs at all. */
	public static synchronized boolean permitted() {
		return permitted;
	}

	/** The server's config protocol if this client cannot use it (a different MejtaCore version), else 0. */
	public static synchronized int incompatibleProtocol() {
		return incompatibleProtocol;
	}

	public static synchronized List<ConfigHandle> configs() {
		return new ArrayList<>(handles.values());
	}

	/** The server's copy of config {@code id}, if it lets this player edit it. */
	public static synchronized Optional<ConfigHandle> find(String id) {
		return Optional.ofNullable(handles.get(id));
	}

	/**
	 * Shows the server's answer to an edit where it can be read: in the open menu's status line (the action bar
	 * is hidden behind the menu) and, for a refusal, also in chat, so it is still there after the menu closes.
	 */
	static void showResult(Minecraft client, boolean ok, String message) {
		if (ok) {
			MenuStatus.show(Component.literal("Saved on the server: " + message).withStyle(ChatFormatting.GREEN));
			return;
		}
		Component text = Component.literal("Server refused the change: " + message).withStyle(ChatFormatting.RED);
		MenuStatus.show(text);
		if (client.player != null) {
			client.gui.getChat().addClientSystemMessage(Component.literal("[MejtaCore] ").withStyle(ChatFormatting.DARK_GRAY).append(text));
		}
	}

	/** Whether {@code handle} is a server's config (edits are sent to the server) rather than a local file. */
	public static boolean isRemote(ConfigHandle handle) {
		return handle instanceof RemoteConfigHandle;
	}

	/** Display name sent by the server for a remote config. */
	public static synchronized String name(ConfigHandle handle) {
		return handle instanceof RemoteConfigHandle remote && remote.name() != null ? remote.name() : handle.id();
	}

	private static void apply(ServerConfigs payload) {
		if (payload.protocol() != ConfigPayloads.PROTOCOL) {
			// Before 1.4.0 this was only logged: Server Settings then said "Loading..." forever.
			boolean first;
			synchronized (RemoteConfigs.class) {
				first = incompatibleProtocol != payload.protocol();
				incompatibleProtocol = payload.protocol();
				received = false;
				handles.clear();
			}
			if (first) {
				MejtaLog.CORE.warn("Server uses config protocol {}, this client {}; remote settings disabled",
						payload.protocol(), ConfigPayloads.PROTOCOL);
			}
			refreshOpenMenu();
			return;
		}
		synchronized (RemoteConfigs.class) {
			incompatibleProtocol = 0;
			received = true;
			permitted = payload.permitted();
			Map<String, RemoteConfigHandle> next = new LinkedHashMap<>();
			for (RemoteConfig remote : payload.configs()) {
				RemoteConfigHandle handle = handles.getOrDefault(remote.id(), new RemoteConfigHandle(remote.id()));
				handle.update(remote);
				next.put(remote.id(), handle);
			}
			handles.clear();
			handles.putAll(next);
		}
		refreshOpenMenu();
	}

	/** Menus show the server's state (e.g. the main menu's "+ 3 of the server's configs"): redraw the open one. */
	private static void refreshOpenMenu() {
		if (Minecraft.getInstance().screen instanceof CardScreen menu) {
			menu.refresh();
		}
	}

	private static synchronized void clear() {
		handles.clear();
		received = false;
		permitted = false;
		incompatibleProtocol = 0;
		PENDING.clear();
	}
}
