package com.mejta.mejtacorpse.menu;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import com.google.gson.JsonElement;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacorpse.MejtaCorpse;
import com.mejta.mejtacorpse.data.CorpseRecord;
import com.mejta.mejtacorpse.data.CorpseStore;
import com.mejta.mejtacorpse.data.SlotLayout;
import com.mejta.mejtacorpse.util.ItemJson;

/**
 * The items of one corpse while its menu is open, shared by everyone viewing it (so two viewers can
 * never take the same item). Slots 0-44 hold the items in menu order ({@link SlotLayout}); 45-53 are the
 * menu's buttons and can never be taken.
 *
 * <p><b>How duplication is prevented.</b> The stored record is the only copy of the items:
 * <ul>
 * <li>Taking items ({@link #removeItem}) first writes the corpse without them to disk, and only then
 *     hands them out. If the write fails, nothing is handed out.</li>
 * <li>Every other change (an item given back, anything the game's menu code changes directly) is
 *     written before the click that caused it finishes ({@link #setChanged}), so before the game can save
 *     the player. A crash at any point can therefore lose the items of an unfinished click, but never
 *     leave them both in the corpse and in a player's save.</li>
 * <li>If a write fails after a change, the corpse is marked broken: its menus close and nothing more can
 *     be taken until the server restarts.</li>
 * </ul>
 */
public final class LiveCorpse implements Container {
	public static final int SLOTS = 54;

	private final CorpseStore store;
	private final HolderLookup.Provider registries;
	private CorpseRecord record;
	private final ItemStack[] items = new ItemStack[SlotLayout.SIZE];
	private final ItemStack[] icons = new ItemStack[SLOTS - SlotLayout.SIZE];
	/** Stored entries that no longer decode (an item of a removed mod): kept in the record, never shown. */
	private final Map<Integer, JsonElement> undecodable = new HashMap<>();
	private Map<Integer, JsonElement> saved;
	private boolean broken;
	private int viewers;

	public LiveCorpse(CorpseStore store, HolderLookup.Provider registries, CorpseRecord record) {
		this.store = store;
		this.registries = registries;
		this.record = record;
		Arrays.fill(items, ItemStack.EMPTY);
		Arrays.fill(icons, ItemStack.EMPTY);
		record.items().forEach((slot, json) -> {
			OptionalInt display = SlotLayout.toDisplay(slot);
			Optional<ItemStack> stack = ItemJson.decode(registries, json);
			if (display.isPresent() && stack.isPresent() && !stack.get().isEmpty()) {
				items[display.getAsInt()] = stack.get();
			} else {
				undecodable.put(slot, json);
			}
		});
		this.saved = record.items();
	}

	public CorpseRecord record() {
		return record;
	}

	public boolean broken() {
		return broken;
	}

	public void addViewer() {
		viewers++;
	}

	/** Returns how many viewers are left. */
	public int removeViewer() {
		viewers = Math.max(0, viewers - 1);
		return viewers;
	}

	public void setIcon(int slot, ItemStack icon) {
		icons[slot - SlotLayout.SIZE] = icon;
	}

	/** Items still in the corpse, counted per item (a stack of 5 counts 5). */
	public int itemCount() {
		int n = 0;
		for (ItemStack s : items) {
			n += s.getCount();
		}
		return n;
	}

	/** Whether the corpse holds nothing any more, not even items it cannot show. */
	public boolean holdsNothing() {
		return isEmpty() && undecodable.isEmpty();
	}

	// ---------------------------------------------------------------- persistence

	private Map<Integer, JsonElement> encode(ItemStack[] state) {
		Map<Integer, JsonElement> out = new TreeMap<>(undecodable);
		for (int d = 0; d < state.length; d++) {
			if (!state[d].isEmpty()) {
				out.put(SlotLayout.toInventory(d).getAsInt(), ItemJson.encode(registries, state[d]));
			}
		}
		return out;
	}

	/** Writes the current items if they differ from the last write. Returns false if that failed. */
	private boolean save() {
		Map<Integer, JsonElement> now;
		try {
			now = encode(items);
		} catch (RuntimeException e) {
			MejtaCorpse.log().error("Corpse {}: an item could not be encoded", record.id(), e);
			return false;
		}
		if (now.equals(saved)) {
			return true;
		}
		// Based on the stored record, which may have new entities since this menu opened.
		CorpseRecord next = store.get(record.id()).orElse(record).withItems(now);
		try {
			store.saveNow(next);
		} catch (IOException e) {
			MejtaCorpse.log().error("Corpse {}: could not save its items", record.id(), e);
			return false;
		}
		record = next;
		saved = now;
		return true;
	}

	/** Saves after a change that already happened; a failure breaks the corpse. */
	public void saveOrBreak() {
		if (!broken && !save()) {
			broken = true;
			MejtaCorpse.manager().broke(this);
		}
	}

	// ---------------------------------------------------------------- moving items out and back

	/**
	 * Takes up to {@code amount} items from menu slot {@code slot}. The corpse without them is on disk
	 * before they are returned; if that write fails, nothing is taken.
	 */
	@Override
	public ItemStack removeItem(int slot, int amount) {
		if (broken || slot < 0 || slot >= SlotLayout.SIZE || amount <= 0 || items[slot].isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack current = items[slot];
		int n = Math.min(amount, current.getCount());
		ItemStack taken = current.copyWithCount(n);
		items[slot] = n == current.getCount() ? ItemStack.EMPTY : current.copyWithCount(current.getCount() - n);
		if (!save()) {
			items[slot] = current;
			return ItemStack.EMPTY;
		}
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return slot >= 0 && slot < SlotLayout.SIZE ? removeItem(slot, items[slot].getCount()) : ItemStack.EMPTY;
	}

	/**
	 * Takes every item out at once (one write), for "Take all". Returns them by menu slot; the corpse is
	 * empty on disk before they are returned. Empty if the write failed.
	 */
	Map<Integer, ItemStack> takeAll() {
		Map<Integer, ItemStack> out = new TreeMap<>();
		if (broken) {
			return out;
		}
		ItemStack[] before = items.clone();
		for (int d = 0; d < items.length; d++) {
			if (!items[d].isEmpty()) {
				out.put(d, items[d]);
				items[d] = ItemStack.EMPTY;
			}
		}
		if (!out.isEmpty() && !save()) {
			System.arraycopy(before, 0, items, 0, items.length);
			return new TreeMap<>();
		}
		return out;
	}

	/**
	 * Puts items that did not fit into the player's inventory back: into their own slot, else onto a
	 * matching stack, else into any free slot. Not written yet; the caller saves once afterwards.
	 */
	void putBack(int slot, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		if (items[slot].isEmpty()) {
			items[slot] = stack;
			return;
		}
		if (ItemStack.isSameItemSameComponents(items[slot], stack) && items[slot].getCount() + stack.getCount() <= items[slot].getMaxStackSize()) {
			items[slot].grow(stack.getCount());
			return;
		}
		for (int d = 0; d < items.length; d++) {
			if (items[d].isEmpty()) {
				items[d] = stack;
				return;
			}
		}
		// Unreachable: the stack came out of a slot of this corpse a moment ago, so that slot has room.
		MejtaCorpse.log().error("Corpse {}: no room to put back {}", record.id(), ItemJson.describe(stack));
	}

	// ---------------------------------------------------------------- Container

	@Override
	public int getContainerSize() {
		return SLOTS;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack s : items) {
			if (!s.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		if (slot < 0 || slot >= SLOTS) {
			return ItemStack.EMPTY;
		}
		return slot < SlotLayout.SIZE ? items[slot] : icons[slot - SlotLayout.SIZE];
	}

	/** Only the menu's own code sets corpse slots (to empty after a removal, or to put items back). */
	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot < 0 || slot >= SlotLayout.SIZE) {
			return;
		}
		items[slot] = stack;
		saveOrBreak();
	}

	/** Called by the game after changing a slot's stack in place: written at once. */
	@Override
	public void setChanged() {
		saveOrBreak();
	}

	@Override
	public boolean stillValid(Player player) {
		return !broken;
	}

	/** Never cleared by the game: a corpse is emptied only by taking its items. */
	@Override
	public void clearContent() {
	}
}
