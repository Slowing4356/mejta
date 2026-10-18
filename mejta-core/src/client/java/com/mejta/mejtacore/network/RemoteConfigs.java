package com.mejta.mejtacore.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.network.ConfigPayloads.EditResult;
import com.mejta.mejtacore.network.ConfigPayloads.RemoteConfig;
import com.mejta.mejtacore.network.ConfigPayloads.RequestConfigs;
import com.mejta.mejtacore.network.ConfigPayloads.ServerConfigs;
import com.mejta.mejtacore.ui.ChestMenuScreen;

/**
 * Client-side copy of the configs the current server lets this player edit. Empty in single-player
 * (where the menu edits the local files directly) and on servers without MejtaCore 1.2+.
 */
public final class RemoteConfigs {
	private static final Map<String, RemoteConfigHandle> handles = new LinkedHashMap<>();
	private static boolean received;
	private static boolean permitted;

	private RemoteConfigs() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ServerConfigs.TYPE, (payload, ctx) -> ctx.client().execute(() -> apply(payload)));
		ClientPlayNetworking.registerGlobalReceiver(EditResult.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (!payload.ok() && ctx.client().player != null) {
				ctx.client().player.sendOverlayMessage(Component.literal(payload.message()).withStyle(ChatFormatting.RED));
			}
		}));
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

	public static synchronized List<ConfigHandle> configs() {
		return new ArrayList<>(handles.values());
	}

	/** Display name sent by the server for a remote config. */
	public static synchronized String name(ConfigHandle handle) {
		return handle instanceof RemoteConfigHandle remote && remote.name() != null ? remote.name() : handle.id();
	}

	private static void apply(ServerConfigs payload) {
		if (payload.protocol() != ConfigPayloads.PROTOCOL) {
			MejtaLog.CORE.warn("Server uses config protocol {}, this client {}; remote settings disabled",
					payload.protocol(), ConfigPayloads.PROTOCOL);
			return;
		}
		synchronized (RemoteConfigs.class) {
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
		if (Minecraft.getInstance().screen instanceof ChestMenuScreen menu) {
			menu.refresh();
		}
	}

	private static synchronized void clear() {
		handles.clear();
		received = false;
		permitted = false;
	}
}
