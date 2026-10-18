package com.mejta.mejtacore.api.menu;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * Ready-made server menus.
 *
 * @since API version 4
 */
public final class ServerMenus {
	private ServerMenus() {
	}

	/**
	 * Asks {@code player} to confirm an action: a green "Confirm" and a red "Cancel" button. Closing the
	 * menu counts as cancelling. {@code onConfirm} runs on the server thread and must re-check
	 * permissions itself: time passes between asking and confirming.
	 */
	public static void confirm(ServerPlayer player, Component title, List<Component> question, Runnable onConfirm, Runnable onCancel) {
		boolean[] decided = {false};
		ServerMenu.builder(title, 3)
				.icon(13, () -> MenuIcons.of(Items.PAPER, title, question))
				.button(11, () -> MenuIcons.of(Items.LIME_CONCRETE, Component.literal("Confirm").withStyle(ChatFormatting.GREEN), question),
						click -> {
							decided[0] = true;
							click.menu().close();
							onConfirm.run();
						})
				.button(15, () -> MenuIcons.of(Items.RED_CONCRETE, Component.literal("Cancel").withStyle(ChatFormatting.RED)),
						click -> click.menu().close())
				.onClose(p -> {
					if (!decided[0]) {
						decided[0] = true;
						onCancel.run();
					}
				})
				.open(player);
	}

	public static void confirm(ServerPlayer player, Component title, List<Component> question, Runnable onConfirm) {
		confirm(player, title, question, onConfirm, () -> {
		});
	}
}
