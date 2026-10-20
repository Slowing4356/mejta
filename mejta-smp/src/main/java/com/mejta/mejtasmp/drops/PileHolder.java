package com.mejta.mejtasmp.drops;

import net.minecraft.world.item.ItemStack;

/**
 * Added to every item entity by {@code ItemEntityMixin}: the items of a pile beyond its visible stack.
 * The visible stack plus {@link #mejtasmp$extra()} is the pile's size.
 */
public interface PileHolder {
	/** Items beyond the visible stack; 0 for an ordinary item entity. */
	int mejtasmp$extra();

	void mejtasmp$setExtra(int extra);

	/** One of the pile's items (count 1), kept even while the visible stack is empty; EMPTY if unknown. */
	ItemStack mejtasmp$prototype();

	/** Whether the entity's name tag is MejtaSMP's pile label (so it may be changed or removed). */
	boolean mejtasmp$labelled();

	void mejtasmp$setLabelled(boolean labelled);
}
