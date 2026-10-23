package com.mejta.mejtamarket.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtamarket.logic.Categories;

/** Item helpers for the market: IDs, categories, and moving items in and out of a player's main inventory. */
public final class MarketItems {
	/** The 36 slots of the hotbar and main inventory; armour and the offhand are never touched. */
	public static final int MAIN_SLOTS = 36;

	private MarketItems() {
	}

	public static String id(ItemStack stack) {
		return id(stack.getItem());
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** The item with this ID ({@code minecraft:} may be left out), if it exists and is not air. */
	public static Optional<Item> byId(String text) {
		String t = text.trim().toLowerCase(Locale.ROOT);
		Identifier key = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
		Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == null || item == Items.AIR ? Optional.empty() : Optional.of(item);
	}

	public static Categories.Category category(ItemStack stack) {
		boolean armor = stack.has(DataComponents.EQUIPPABLE) && !(stack.getItem() instanceof BlockItem);
		boolean tool = stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || (stack.has(DataComponents.MAX_DAMAGE) && !armor);
		return Categories.of(new Categories.ItemFacts(id(stack), stack.getItem() instanceof BlockItem, stack.has(DataComponents.FOOD), armor, tool));
	}

	/** Whether a buy order for {@code itemId} accepts this stack: the same item with no extra data. */
	public static boolean plainMatch(ItemStack stack, String itemId) {
		return !stack.isEmpty() && stack.getComponentsPatch().isEmpty() && id(stack).equals(itemId);
	}

	/** How many items like {@code template} fit in the player's main inventory. */
	public static int space(Inventory inventory, ItemStack template) {
		int max = template.getMaxStackSize();
		int space = 0;
		for (int slot = 0; slot < MAIN_SLOTS; slot++) {
			ItemStack s = inventory.getItem(slot);
			if (s.isEmpty()) {
				space += max;
			} else if (ItemStack.isSameItemSameComponents(s, template) && s.getCount() < max) {
				space += max - s.getCount();
			}
		}
		return space;
	}

	/** Gives {@code count} items like {@code template}; anything that does not fit (it should) drops at the player's feet. */
	public static void give(ServerPlayer player, ItemStack template, int count) {
		int max = Math.max(1, template.getMaxStackSize());
		while (count > 0) {
			int n = Math.min(max, count);
			ItemStack stack = template.copyWithCount(n);
			player.getInventory().add(stack);
			if (!stack.isEmpty()) {
				player.drop(stack, false);
			}
			count -= n;
		}
	}

	/** Plain items of {@code itemId} the player holds in the main inventory. */
	public static int countPlain(Inventory inventory, String itemId) {
		int n = 0;
		for (int slot = 0; slot < MAIN_SLOTS; slot++) {
			ItemStack s = inventory.getItem(slot);
			if (plainMatch(s, itemId)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/**
	 * Which slots {@link #takePlain} would take from, with their full stacks as they are now (copies), or
	 * empty if the player holds fewer than {@code count}.
	 */
	public static Optional<Map<Integer, ItemStack>> planTake(Inventory inventory, String itemId, int count) {
		Map<Integer, ItemStack> plan = new LinkedHashMap<>();
		int left = count;
		for (int slot = MAIN_SLOTS - 1; slot >= 0 && left > 0; slot--) {
			// The main inventory first, the hotbar last: players keep tools in the hotbar.
			ItemStack s = inventory.getItem(slot);
			if (plainMatch(s, itemId)) {
				plan.put(slot, s.copy());
				left -= s.getCount();
			}
		}
		return left > 0 ? Optional.empty() : Optional.of(plan);
	}

	/** Takes {@code count} items from the planned slots; returns the slots' stacks as they were, to put back on failure. */
	public static Map<Integer, ItemStack> take(Inventory inventory, Map<Integer, ItemStack> plan, int count) {
		Map<Integer, ItemStack> old = new LinkedHashMap<>();
		int left = count;
		for (int slot : plan.keySet()) {
			if (left <= 0) {
				break;
			}
			ItemStack s = inventory.getItem(slot);
			old.put(slot, s.copy());
			int n = Math.min(left, s.getCount());
			s.shrink(n);
			inventory.setItem(slot, s.isEmpty() ? ItemStack.EMPTY : s);
			left -= n;
		}
		return old;
	}

	/** The slot holding the player's main-hand stack, or -1. */
	public static int mainHandSlot(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			if (inventory.getItem(slot) == held) {
				return slot;
			}
		}
		return -1;
	}

	/** An icon item for an ID, or paper if it does not exist. */
	public static Item iconOf(String id) {
		return byId(id).orElse(Items.PAPER);
	}

	/** Fallback icon when nothing else is known. */
	public static ItemStack paper() {
		return new ItemStack(Items.PAPER);
	}
}
