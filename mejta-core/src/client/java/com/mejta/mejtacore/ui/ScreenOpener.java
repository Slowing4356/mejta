package com.mejta.mejtacore.ui;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import com.mejta.mejtacore.logging.Diagnostics;

/**
 * Opens a screen at the end of the next client tick.
 *
 * <p>Needed for commands: the chat screen closes itself right after executing a command, which
 * would immediately close a screen opened from inside the command.
 */
public final class ScreenOpener {
	private static volatile Supplier<Screen> pending;

	private ScreenOpener() {
	}

	public static void open(Supplier<Screen> screen) {
		pending = screen;
	}

	public static void tick(Minecraft client) {
		Supplier<Screen> next = pending;
		if (next == null) {
			return;
		}
		pending = null;
		try {
			client.setScreen(next.get());
		} catch (RuntimeException e) {
			Diagnostics.error("ui", "Could not open menu", e);
		}
	}
}
