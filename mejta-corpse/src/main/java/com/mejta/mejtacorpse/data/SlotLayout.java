package com.mejta.mejtacorpse.data;

import java.util.OptionalInt;

/**
 * Where each of the owner's inventory slots appears in the corpse menu, laid out like the inventory
 * screen: the main inventory on top, the hotbar below it, then armour (head to feet) and the offhand.
 *
 * <pre>
 * menu  0-26  = inventory  9-35 (main)
 * menu 27-35  = inventory  0-8  (hotbar)
 * menu 36-39  = inventory 39-36 (head, chest, legs, feet)
 * menu 40     = inventory 40    (offhand)
 * menu 41-44  = inventory 41-44 (extra slots added by other mods, if any)
 * </pre>
 */
public final class SlotLayout {
	/** Item slots in the menu (five rows). */
	public static final int SIZE = 45;
	/** Inventory slots a corpse can hold. */
	public static final int MAX_INVENTORY_SLOTS = 45;

	private SlotLayout() {
	}

	/** The inventory slot shown in menu slot {@code display}, or empty outside 0-44. */
	public static OptionalInt toInventory(int display) {
		if (display < 0 || display >= SIZE) {
			return OptionalInt.empty();
		}
		if (display < 27) {
			return OptionalInt.of(display + 9);
		}
		if (display < 36) {
			return OptionalInt.of(display - 27);
		}
		if (display < 40) {
			return OptionalInt.of(39 - (display - 36));
		}
		return OptionalInt.of(display);
	}

	/** The menu slot that shows inventory slot {@code inventory}, or empty outside 0-44. */
	public static OptionalInt toDisplay(int inventory) {
		if (inventory < 0 || inventory >= MAX_INVENTORY_SLOTS) {
			return OptionalInt.empty();
		}
		if (inventory < 9) {
			return OptionalInt.of(inventory + 27);
		}
		if (inventory < 36) {
			return OptionalInt.of(inventory - 9);
		}
		if (inventory < 40) {
			return OptionalInt.of(36 + (39 - inventory));
		}
		return OptionalInt.of(inventory);
	}
}
