package com.mejta.mejtautils.inspect;

import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtautils.MejtaUtils;

/**
 * Base of every inspection menu (/invsee, /ecsee, offline snapshots).
 *
 * <ul>
 * <li>Locked slots (info buttons, crafting grid, everything in view mode) never reach vanilla's click
 * handling, so no click type can take, place or swap an item there; the client is re-synced after.</li>
 * <li>In edit mode, the remaining slots are the target's real containers: vanilla moves items between
 * them and the viewer's inventory on the server thread, so there is never a copy to duplicate.</li>
 * <li>Before every click and every refresh the target and the viewer's permission are re-checked;
 * the menu closes as soon as either fails (e.g. the target logged out).</li>
 * </ul>
 */
public abstract class InspectMenu extends AbstractContainerMenu {
	protected final ServerPlayer viewer;
	protected final int topSize;
	protected final boolean editable;
	private boolean closed;
	private int ticks;

	protected InspectMenu(MenuType<?> type, int containerId, ServerPlayer viewer, int rows, boolean editable) {
		super(type, containerId);
		this.viewer = viewer;
		this.topSize = rows * 9;
		// Staff mode discards the staff member's inventory on exit: moving items into it would destroy them.
		this.editable = editable && !MejtaUtils.staffMode().isActive(viewer.getUUID());
	}

	protected static MenuType<?> chestType(int rows) {
		return switch (rows) {
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 6 -> MenuType.GENERIC_9x6;
			default -> throw new IllegalArgumentException("rows " + rows);
		};
	}

	/** Whether the top-area slot is an icon or display slot (never vanilla-handled). */
	protected abstract boolean isLocked(int slot);

	/** Target still present and viewer still allowed. Checked before every click and on every refresh. */
	protected abstract boolean stillAllowed();

	/** Re-creates icons and display copies. Called every {@code invsee.refresh_ticks}. */
	protected abstract void refresh();

	/** A click on a locked slot of the top area. */
	protected void onLockedClick(int slot, ContainerInput input, int button) {
	}

	/** A click in view mode on the viewer's own inventory (offline editing uses it). */
	protected void onViewerInventoryClick(int slot, ContainerInput input, int button) {
	}

	/** Called once when the menu closes, for any reason. */
	protected void onClosed() {
	}

	public boolean isEditable() {
		return editable;
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player who) {
		if (closed || who != viewer) {
			return;
		}
		if (!stillAllowed()) {
			close();
			return;
		}
		boolean top = slotId >= 0 && slotId < topSize;
		if (top && isLocked(slotId)) {
			onLockedClick(slotId, input, button);
		} else if (!editable) {
			if (slotId >= topSize) {
				onViewerInventoryClick(slotId, input, button);
			}
		} else {
			super.clicked(slotId, button, input, who);
			return;
		}
		if (!closed && viewer.containerMenu == this) {
			sendAllDataToRemote(); // undo what the client predicted
		}
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return editable && !(slot instanceof LockedSlot) && super.canTakeItemForPickAll(stack, slot);
	}

	@Override
	public boolean stillValid(Player who) {
		return !closed && who == viewer;
	}

	@Override
	public void broadcastChanges() {
		if (!closed) {
			if (++ticks % MejtaUtils.config().get(com.mejta.mejtautils.UtilsSettings.INVSEE_REFRESH_TICKS) == 0) {
				if (!stillAllowed()) {
					close();
					return;
				}
				refresh();
			}
		}
		super.broadcastChanges();
	}

	@Override
	public void removed(Player who) {
		super.removed(who);
		if (!closed) {
			closed = true;
			onClosed();
		}
	}

	public void close() {
		if (viewer.containerMenu == this) {
			viewer.closeContainer();
		} else {
			removed(viewer);
		}
	}

	public boolean isClosed() {
		return closed;
	}

	/** Shift-click: only in edit mode, between the target's slots ({@code [0, targetEnd)}) and the viewer's inventory. */
	protected ItemStack quickMove(int index, int targetEnd) {
		if (!editable || index < 0 || index >= slots.size()) {
			return ItemStack.EMPTY;
		}
		Slot slot = slots.get(index);
		if (slot instanceof LockedSlot || !slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		if (index < targetEnd) {
			if (!moveItemStackTo(stack, topSize, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (index >= topSize) {
			if (!moveItemStackTo(stack, 0, Math.min(targetEnd, 36), false)) {
				return ItemStack.EMPTY;
			}
		} else {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return original;
	}

	/** A slot of the target's container: vanilla behaviour when editable, untouchable otherwise. */
	protected static class TargetSlot extends Slot {
		private final boolean editable;

		TargetSlot(Container container, int index, boolean editable) {
			super(container, index, 0, 0);
			this.editable = editable;
		}

		@Override
		public boolean mayPickup(Player player) {
			return editable && super.mayPickup(player);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return editable;
		}

		@Override
		public boolean allowModification(Player player) {
			return editable && super.allowModification(player);
		}

		@Override
		public ItemStack remove(int amount) {
			return editable ? super.remove(amount) : ItemStack.EMPTY;
		}

		@Override
		public Optional<ItemStack> tryRemove(int count, int decrement, Player player) {
			return editable ? super.tryRemove(count, decrement, player) : Optional.empty();
		}

		@Override
		public ItemStack safeInsert(ItemStack stack, int count) {
			return editable ? super.safeInsert(stack, count) : stack;
		}
	}

	/** An icon or display copy; can never be taken, replaced or filled. */
	protected static final class LockedSlot extends Slot {
		LockedSlot(Container container, int index) {
			super(container, index, 0, 0);
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean allowModification(Player player) {
			return false;
		}

		@Override
		public ItemStack remove(int amount) {
			return ItemStack.EMPTY;
		}

		@Override
		public Optional<ItemStack> tryRemove(int count, int decrement, Player player) {
			return Optional.empty();
		}

		@Override
		public ItemStack safeInsert(ItemStack stack, int count) {
			return stack;
		}

		@Override
		public ItemStack safeInsert(ItemStack stack) {
			return stack;
		}
	}
}
