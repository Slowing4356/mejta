package com.mejta.mejtacore.ui;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;

/**
 * Feedback of the last action (e.g. the server's answer to an edit), shown by the open MejtaCore screen. The
 * action bar and chat are behind a screen's darkened background, where a refusal used to go unnoticed. Static,
 * so it stays visible across pages and the text input screen. Call on the client thread.
 */
public final class MenuStatus {
	private static final long STATUS_MILLIS = 6_000;

	private static @Nullable Component status;
	private static long statusUntil;

	private MenuStatus() {
	}

	/** Shows {@code message} in the open (or next opened) menu for a few seconds. */
	public static void show(Component message) {
		String text = message.getString();
		status = text.length() <= 120 ? message : Component.literal(text.substring(0, 117) + "...").setStyle(message.getStyle());
		statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
	}

	/** The status line currently shown, if any. */
	public static @Nullable Component current() {
		return status != null && System.currentTimeMillis() < statusUntil ? status : null;
	}
}
