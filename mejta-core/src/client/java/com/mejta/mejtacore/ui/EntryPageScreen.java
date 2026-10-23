package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.mejta.mejtacore.api.client.MenuEntry;

/**
 * A page of addon {@link MenuEntry}s ({@code MenuContext.openPage}, {@code MejtaCoreClientAPI.openPage}), drawn as
 * cards. {@code null} entries, which left a slot empty in the old chest-style pages, are skipped.
 */
public class EntryPageScreen extends CardScreen {
	private final Supplier<List<MenuEntry>> entries;

	public EntryPageScreen(Component title, Supplier<List<MenuEntry>> entries, @Nullable Screen parent) {
		super(title, parent);
		this.entries = entries;
	}

	@Override
	protected List<Item> buildItems() {
		List<Item> items = new ArrayList<>();
		for (MenuEntry entry : entries.get()) {
			if (entry != null && entry != MenuItems.filler()) {
				items.add(entryCard(entry));
			}
		}
		return items;
	}
}
