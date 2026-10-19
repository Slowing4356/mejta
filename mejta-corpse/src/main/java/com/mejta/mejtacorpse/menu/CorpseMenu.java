package com.mejta.mejtacorpse.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacorpse.MejtaCorpse;
import com.mejta.mejtacorpse.data.SlotLayout;

/**
 * A corpse's menu: a six-row chest, so vanilla clients show it. Rows 1-5 are the items, which can be
 * taken but never put in; row 6 is buttons. Every way the game moves items out of a menu ends in
 * {@link LiveCorpse#removeItem} (written before the items are handed out), and every click ends with a
 * save of anything else that changed.
 */
public final class CorpseMenu extends AbstractContainerMenu {
	/** First player-inventory slot of the menu. */
	static final int PLAYER_START = LiveCorpse.SLOTS;
	static final int PLAYER_END = PLAYER_START + 36;

	private final LiveCorpse corpse;
	private final boolean remote;
	/** Items this viewer took, counted per item, for the audit log. */
	private int taken;

	public CorpseMenu(int id, Inventory inventory, LiveCorpse corpse, boolean remote) {
		super(MenuType.GENERIC_9x6, id);
		this.corpse = corpse;
		this.remote = remote;
		for (int row = 0; row < 6; row++) {
			for (int col = 0; col < 9; col++) {
				int slot = row * 9 + col;
				addSlot(slot < SlotLayout.SIZE ? new ItemSlot(corpse, slot, 8 + col * 18, 18 + row * 18)
						: new ButtonSlot(corpse, slot, 8 + col * 18, 18 + row * 18));
			}
		}
		int offset = (6 - 4) * 18;
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
			}
		}
		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, 8 + col * 18, 161 + offset));
		}
	}

	public LiveCorpse corpse() {
		return corpse;
	}

	public boolean remote() {
		return remote;
	}

	public int taken() {
		return taken;
	}

	void addTaken(int count) {
		taken += Math.max(0, count);
	}

	@Override
	public void clicked(int slotId, int button, ClickType type, Player player) {
		if (corpse.broken()) {
			return;
		}
		if (slotId >= SlotLayout.SIZE && slotId < LiveCorpse.SLOTS) {
			if (type == ClickType.PICKUP) {
				MejtaCorpse.manager().buttonClicked(this, slotId, player);
			}
			sendAllDataToRemote();
			return;
		}
		int before = corpse.itemCount();
		try {
			if (slotId >= 0 && slotId < SlotLayout.SIZE && type == ClickType.SWAP) {
				swapToInventory(slotId, button, player);
			} else {
				super.clicked(slotId, button, type, player);
			}
		} finally {
			// Anything the game's code changed in place is written before this click ends.
			corpse.saveOrBreak();
			addTaken(before - corpse.itemCount());
			if (corpse.broken()) {
				sendAllDataToRemote();
			}
		}
	}

	/**
	 * A number key (or the offhand key) over a corpse slot: moves the item into that hotbar slot if it is
	 * empty. Nothing is ever swapped into the corpse.
	 */
	private void swapToInventory(int slotId, int button, Player player) {
		Inventory inventory = player.getInventory();
		boolean hotbar = button >= 0 && button < 9;
		boolean offhand = button == 40;
		if (!hotbar && !offhand || !inventory.getItem(button).isEmpty()) {
			return;
		}
		ItemStack item = corpse.getItem(slotId);
		if (item.isEmpty()) {
			return;
		}
		ItemStack moved = corpse.removeItem(slotId, item.getCount());
		if (!moved.isEmpty()) {
			inventory.setItem(button, moved);
		}
	}

	/**
	 * "Take all": the corpse is emptied on disk first, then the items go to the player. The owner gets
	 * each item back into the slot it was in (armour is worn again) where that slot is free; everything
	 * else goes wherever it fits. What does not fit stays in the corpse. Returns the number of items moved.
	 */
	public int takeAll(Player player, boolean owner) {
		Inventory inventory = player.getInventory();
		java.util.Map<Integer, ItemStack> out = corpse.takeAll();
		int moved = 0;
		for (java.util.Map.Entry<Integer, ItemStack> e : out.entrySet()) {
			ItemStack stack = e.getValue();
			int count = stack.getCount();
			int original = SlotLayout.toInventory(e.getKey()).getAsInt();
			if (owner && original < inventory.getContainerSize() && inventory.getItem(original).isEmpty()) {
				inventory.setItem(original, stack);
				moved += count;
				continue;
			}
			inventory.add(stack);
			moved += count - stack.getCount();
			corpse.putBack(e.getKey(), stack);
		}
		corpse.saveOrBreak();
		addTaken(moved);
		sendAllDataToRemote();
		return moved;
	}

	/** Shift-click: from the corpse into the player's inventory only. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < 0 || index >= SlotLayout.SIZE) {
			return ItemStack.EMPTY;
		}
		ItemStack item = corpse.getItem(index);
		if (item.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack moving = corpse.removeItem(index, item.getCount());
		if (moving.isEmpty()) {
			return ItemStack.EMPTY;
		}
		moveItemStackTo(moving, PLAYER_START, PLAYER_END, true);
		if (!moving.isEmpty()) {
			corpse.putBack(index, moving);
		}
		// Empty: the game must not repeat the move.
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return !(slot instanceof ButtonSlot);
	}

	@Override
	public boolean stillValid(Player player) {
		return MejtaCorpse.manager().stillValid(this, player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		MejtaCorpse.manager().closed(this, player);
	}

	/** A corpse item: can be taken, never put in. */
	static final class ItemSlot extends Slot {
		ItemSlot(LiveCorpse corpse, int slot, int x, int y) {
			super(corpse, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}
	}

	/** A button in the bottom row: shown, never moved. */
	static final class ButtonSlot extends Slot {
		ButtonSlot(LiveCorpse corpse, int slot, int x, int y) {
			super(corpse, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}
	}
}
