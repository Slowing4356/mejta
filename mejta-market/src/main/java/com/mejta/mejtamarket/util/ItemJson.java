package com.mejta.mejtamarket.util;

import java.util.Optional;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Item stacks as JSON, with every component (enchantments, names, contents), using the game's own
 * item codec. Used for the items the market holds.
 */
public final class ItemJson {
	private ItemJson() {
	}

	/** @throws IllegalStateException if the stack cannot be encoded (it is never empty here) */
	public static JsonElement encode(HolderLookup.Provider registries, ItemStack stack) {
		if (stack.isEmpty()) {
			throw new IllegalArgumentException("empty stacks are stored as absent, not encoded");
		}
		return ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), stack)
				.getOrThrow(message -> new IllegalStateException("Cannot save item " + stack + ": " + message));
	}

	/** The stack, or empty if the JSON no longer describes a valid item (e.g. an item of a removed mod). */
	public static Optional<ItemStack> decode(HolderLookup.Provider registries, JsonElement json) {
		return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json).result();
	}

	/** Whether {@code stack} is exactly the encoded item (same item, count and components). */
	public static boolean matches(HolderLookup.Provider registries, ItemStack stack, JsonElement json) {
		return decode(registries, json).map(decoded -> ItemStack.matches(stack, decoded)).orElse(false);
	}

	/** Short description for audit entries, e.g. {@code minecraft:diamond x3}. */
	public static String describe(ItemStack stack) {
		if (stack.isEmpty()) {
			return "empty";
		}
		return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount() + (stack.getComponentsPatch().isEmpty() ? "" : " (with components)");
	}
}
