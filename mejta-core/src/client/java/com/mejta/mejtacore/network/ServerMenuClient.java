package com.mejta.mejtacore.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.network.MenuPayloads.ClickMenu;
import com.mejta.mejtacore.network.MenuPayloads.CloseMenu;
import com.mejta.mejtacore.network.MenuPayloads.MenuClosed;
import com.mejta.mejtacore.network.MenuPayloads.MenuPreference;
import com.mejta.mejtacore.network.MenuPayloads.OpenMenu;
import com.mejta.mejtacore.ui.CardScreen;
import com.mejta.mejtacore.ui.ServerMenuScreen;

/**
 * Client side of server menus shown as screens: tells the server whether this player wants them
 * ({@code menus_as_screens}, off by default), opens and redraws {@link ServerMenuScreen}s and sends clicks back.
 */
public final class ServerMenuClient {
	private static boolean lastSent;

	private ServerMenuClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(OpenMenu.TYPE, (payload, ctx) -> ctx.client().execute(() -> open(ctx.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(CloseMenu.TYPE, (payload, ctx) -> ctx.client().execute(() -> {
			if (ctx.client().screen instanceof ServerMenuScreen screen && screen.session() == payload.session()) {
				screen.closeFromServer();
			}
		}));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> sendPreference(true)));
		MejtaCore.coreConfig().addChangeListener(() -> Minecraft.getInstance().execute(() -> sendPreference(false)));
	}

	private static void open(Minecraft client, OpenMenu payload) {
		if (client.screen instanceof ServerMenuScreen screen && screen.session() == payload.session()) {
			screen.update(payload);
		} else if (!payload.refresh()) {
			client.setScreen(new ServerMenuScreen(payload));
		}
		// A redraw of a menu that is no longer shown (the player closed it a moment ago) is ignored.
	}

	/** Sends the setting to the server on joining, and later only when it changed. */
	private static void sendPreference(boolean always) {
		boolean screens = MejtaCore.coreConfig().get(CoreSettings.SERVER_MENU_SCREENS);
		if (!always && screens == lastSent) {
			return;
		}
		if (canSend(MenuPreference.TYPE)) {
			ClientPlayNetworking.send(new MenuPreference(screens));
			lastSent = screens;
		}
	}

	public static void click(int session, int slot, CardScreen.Click click) {
		String type = switch (click.button()) {
			case 1 -> click.shift() ? "SHIFT_RIGHT" : "RIGHT";
			case 2 -> "MIDDLE";
			default -> click.shift() ? "SHIFT_LEFT" : "LEFT";
		};
		if (canSend(ClickMenu.TYPE)) {
			ClientPlayNetworking.send(new ClickMenu(session, slot, type));
		}
	}

	public static void closed(int session) {
		if (canSend(MenuClosed.TYPE)) {
			ClientPlayNetworking.send(new MenuClosed(session));
		}
	}

	private static boolean canSend(CustomPacketPayload.Type<?> type) {
		try {
			return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(type);
		} catch (IllegalStateException | IllegalArgumentException e) {
			return false; // not connected
		}
	}
}
