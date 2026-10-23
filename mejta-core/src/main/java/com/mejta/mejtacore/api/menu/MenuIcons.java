package com.mejta.mejtacore.api.menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import net.minecraft.world.item.component.TooltipDisplay;

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

	/**
	 * A gray glass pane with no tooltip, for the empty slots around a menu's buttons. Players with MejtaCore who
	 * see menus as screens do not see it at all.
	 *
	 * @since API version 7
	 */
	public static ItemStack filler() {
		ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
		pane.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
		return pane;
	}

	/**
	 * Whether {@code stack} shows no tooltip, like {@link #filler()}: it is decoration, not a button.
	 *
	 * @since API version 7
	 */
	public static boolean isFiller(ItemStack stack) {
		return stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).hideTooltip();
	}

	/**
	 * The usual "Back" button icon (an arrow), naming where it leads, e.g. "Back to homes".
	 *
	 * @since API version 7
	 */
	public static ItemStack back(String to) {
		return of(Items.ARROW, Component.literal(to == null || to.isBlank() ? "Back" : "Back to " + to).withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * The usual "Close" button icon (a barrier).
	 *
	 * @since API version 7
	 */
	public static ItemStack close() {
		return of(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED));
	}

	/**
	 * A previous/next page button showing the page it leads to (1-based) out of {@code pages}.
	 *
	 * @since API version 7
	 */
	public static ItemStack page(boolean next, int toPage, int pages) {
		ItemStack stack = of(Items.SPECTRAL_ARROW, Component.literal(next ? "Next page \u2192" : "\u2190 Previous page").withStyle(ChatFormatting.YELLOW),
				line("Page " + toPage + " of " + pages));
		stack.setCount(Math.max(1, Math.min(toPage, 64)));
		return stack;
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
