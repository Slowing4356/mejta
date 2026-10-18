package com.mejta.mejtacore.ui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.client.MenuAction;
import com.mejta.mejtacore.api.client.MenuEntry;

/** Small helpers for building menu entries. */
public final class MenuItems {
	private static final MenuEntry FILLER = MenuEntry.builder("filler")
			.icon(() -> new ItemStack(Items.GRAY_STAINED_GLASS_PANE))
			.title(Component.empty())
			.build();

	private MenuItems() {
	}

	/** Decorative glass pane used in the navigation row; has no tooltip and ignores clicks. */
	public static MenuEntry filler() {
		return FILLER;
	}

	public static MenuEntry simple(String id, Item icon, Component title, MenuAction action) {
		return MenuEntry.builder(id).icon(() -> new ItemStack(icon)).title(title).onClick(action).build();
	}

	/** Resolves an item ID such as {@code minecraft:book}; unknown IDs give a barrier. */
	public static ItemStack iconFor(String itemId) {
		Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || item == Items.AIR) {
			return new ItemStack(Items.BARRIER);
		}
		return new ItemStack(item);
	}
}
