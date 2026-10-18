package com.mejta.mejtacore.api.menu;

import java.util.ArrayList;
import java.util.List;

import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Builds icon stacks for {@link ServerMenu}s: a name and lore lines, shown without the default italics.
 *
 * @since API version 4
 */
public final class MenuIcons {
	private MenuIcons() {
	}

	public static ItemStack of(Item item, Component name, Component... lore) {
		return of(new ItemStack(item), name, List.of(lore));
	}

	public static ItemStack of(Item item, Component name, List<Component> lore) {
		return of(new ItemStack(item), name, lore);
	}

	/** Names a copy of {@code base} (its count and components are kept). */
	public static ItemStack of(ItemStack base, Component name, List<Component> lore) {
		ItemStack stack = base.copy();
		stack.set(DataComponents.CUSTOM_NAME, plain(name));
		List<Component> lines = new ArrayList<>(lore.size());
		for (Component line : lore) {
			lines.add(plain(line));
		}
		stack.set(DataComponents.LORE, new ItemLore(lines));
		return stack;
	}

	/** A player head showing {@code profile}'s skin (resolved by the client). */
	public static ItemStack head(GameProfile profile, Component name, List<Component> lore) {
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		return of(head, name, lore);
	}

	/** A gray line, the usual style for lore. */
	public static Component line(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static Component plain(Component text) {
		MutableComponent copy = text.copy();
		return copy.withStyle(style -> style.withItalic(false));
	}
}
