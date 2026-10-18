package com.mejta.mejtacore.api.menu;

import net.minecraft.server.level.ServerPlayer;

/**
 * A click on a button of a {@link ServerMenu}. Handlers run on the server thread.
 *
 * @param player who clicked
 * @param slot   the clicked slot (0 = top left)
 * @param type   how they clicked
 * @param menu   the open menu, to refresh or close it
 * @since API version 4
 */
public record MenuClick(ServerPlayer player, int slot, Type type, ServerMenu.Handle menu) {
	public enum Type {
		LEFT,
		RIGHT,
		SHIFT_LEFT,
		SHIFT_RIGHT,
		MIDDLE,
		DROP,
		CTRL_DROP,
		/** A hotbar number key (1-9) pressed over the slot. */
		NUMBER_KEY,
		/** The offhand swap key pressed over the slot. */
		OFFHAND_SWAP,
		DOUBLE_CLICK,
		DRAG;

		public boolean isShift() {
			return this == SHIFT_LEFT || this == SHIFT_RIGHT;
		}

		public boolean isRight() {
			return this == RIGHT || this == SHIFT_RIGHT;
		}
	}
}
