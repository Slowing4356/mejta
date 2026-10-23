package com.mejta.mejtacore.api.client;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Passed to {@link MenuAction}s; lets an entry navigate between menus. */
public interface MenuContext {
	/** Which mouse button was used: 0 = left, 1 = right, 2 = middle. */
	int button();

	boolean shiftDown();

	boolean controlDown();

	/** The local player (never null while a MejtaCore menu is open in a world). */
	LocalPlayer player();

	/** The menu screen the click happened in. */
	Screen screen();

	/**
	 * Opens a new page of entries (cards since 1.7.0). Its "Back" button returns to the current menu.
	 * Entries are re-read from the supplier whenever the page is shown or {@link #refresh()} is called.
	 */
	void openPage(Component title, Supplier<List<MenuEntry>> entries);

	/** Opens any screen; when it closes it should return to {@link #screen()} (pass it as parent). */
	void openScreen(Screen screen);

	/** Rebuilds the current page's entries, e.g. after a value changed. */
	void refresh();

	/** Returns to the previous menu (or closes, if this is the main menu). */
	void back();

	void close();
}
