package com.mejta.mejtacore.menu;

import java.util.Map;
import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.logging.Diagnostics;

/**
 * Server side of a {@link ServerMenu}. The client sees an ordinary chest. Vanilla's click handling is
 * never called, so no click type (pickup, shift, number keys, drag, double click, drop) can move an
 * item; after every click the client's view is replaced by the server's.
 */
public final class ButtonMenu extends AbstractContainerMenu implements ServerMenu.Handle {
	private final ServerMenu definition;
	private final ServerPlayer player;
	private final SimpleContainer icons;
	private final int size;
	private int ticks;
	private boolean closed;

	private ButtonMenu(MenuType<?> type, int containerId, Inventory playerInventory, ServerMenu definition, ServerPlayer player) {
		super(type, containerId);
		this.definition = definition;
		this.player = player;
		this.size = definition.rows() * 9;
		this.icons = new SimpleContainer(size);
		for (int i = 0; i < size; i++) {
			addSlot(new LockedSlot(icons, i));
		}
		addStandardInventorySlots(playerInventory, 0, 0);
		render();
	}

	public static boolean open(ServerPlayer player, ServerMenu definition) {
		if (!definition.validWhile().test(player)) {
			return false;
		}
		MenuType<?> type = switch (definition.rows()) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
		if (player.containerMenu != player.inventoryMenu) {
			// Replace the open container without telling the client it closed: the client then swaps one screen for
			// the next and the cursor stays where it was, instead of being re-centred as when a screen opens from
			// the game. The old menu's removed() (and so its onClose) still runs here.
			player.doCloseContainer();
		}
		return player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, p) -> new ButtonMenu(type, containerId, inventory, definition, player),
				definition.title())).isPresent();
	}

	private void render() {
		for (int i = 0; i < size; i++) {
			ServerMenu.Button button = definition.buttons().get(i);
			ItemStack stack = ItemStack.EMPTY;
			if (button != null) {
				try {
					stack = button.icon().get().copy();
				} catch (RuntimeException e) {
					Diagnostics.error("menu", "Icon of slot " + i + " in menu '" + definition.title().getString() + "' failed", e);
				}
			}
			icons.setItem(i, stack);
		}
	}

	@Override
	public void clicked(int slotId, int clickData, ContainerInput input, Player who) {
		// Nothing here calls super.clicked: no item ever moves, in the menu or in the player's inventory.
		if (closed || who != player) {
			return;
		}
		if (!definition.validWhile().test(player)) {
			close();
			return;
		}
		if (slotId >= 0 && slotId < size) {
			ServerMenu.Button button = definition.buttons().get(slotId);
			Optional<MenuClick.Type> type = typeOf(input, clickData);
			if (button != null && type.isPresent()) {
				try {
					button.handler().onClick(new MenuClick(player, slotId, type.get(), this));
				} catch (RuntimeException e) {
					Diagnostics.error("menu", "Click handler of slot " + slotId + " in menu '" + definition.title().getString()
							+ "' failed", e);
				}
			}
		}
		if (!closed && player.containerMenu == this) {
			sendAllDataToRemote(); // undo whatever the client predicted
		}
	}

	static Optional<MenuClick.Type> typeOf(ContainerInput input, int button) {
		return Optional.ofNullable(switch (input) {
			case PICKUP -> button == 0 ? MenuClick.Type.LEFT : button == 1 ? MenuClick.Type.RIGHT : null;
			case QUICK_MOVE -> button == 0 ? MenuClick.Type.SHIFT_LEFT : button == 1 ? MenuClick.Type.SHIFT_RIGHT : null;
			case SWAP -> button == Inventory.SLOT_OFFHAND ? MenuClick.Type.OFFHAND_SWAP : MenuClick.Type.NUMBER_KEY;
			case CLONE -> MenuClick.Type.MIDDLE;
			case THROW -> button == 0 ? MenuClick.Type.DROP : MenuClick.Type.CTRL_DROP;
			case QUICK_CRAFT -> MenuClick.Type.DRAG;
			case PICKUP_ALL -> MenuClick.Type.DOUBLE_CLICK;
		});
	}

	@Override
	public ItemStack quickMoveStack(Player who, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return false;
	}

	@Override
	public boolean stillValid(Player who) {
		return !closed && who == player && player.isAlive();
	}

	@Override
	public void broadcastChanges() {
		if (!closed) {
			ticks++;
			int every = definition.refreshTicks();
			if (every > 0 && ticks % every == 0) {
				if (!definition.validWhile().test(player)) {
					close();
					return;
				}
				render();
			}
		}
		super.broadcastChanges();
	}

	@Override
	public void removed(Player who) {
		super.removed(who);
		if (!closed) {
			closed = true;
			try {
				definition.onClose().accept(player);
			} catch (RuntimeException e) {
				Diagnostics.error("menu", "Close handler of menu '" + definition.title().getString() + "' failed", e);
			}
		}
	}

	@Override
	public void refresh() {
		if (!closed) {
			render();
			broadcastChanges();
		}
	}

	@Override
	public void close() {
		if (player.containerMenu == this) {
			player.closeContainer(); // calls removed(), which runs onClose once
		} else {
			removed(player);
		}
	}

	/** Shows an icon; can never be taken, replaced or filled by a player. */
	private static final class LockedSlot extends Slot {
		LockedSlot(SimpleContainer container, int index) {
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

	/** For diagnostics and tests. */
	public Map<Integer, ServerMenu.Button> buttons() {
		return definition.buttons();
	}
}
