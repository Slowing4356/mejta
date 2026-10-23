package com.mejta.mejtacore.api.menu;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
						click -> decide(click, decided, onConfirm))
				.button(15, () -> MenuIcons.of(Items.RED_CONCRETE, Component.literal("Cancel").withStyle(ChatFormatting.RED)),
						click -> decide(click, decided, onCancel))
				.onClose(p -> {
					if (!decided[0]) {
						decided[0] = true;
						onCancel.run();
					}
				})
				.open(player);
	}

	/**
	 * Runs the answer first and closes the question only if the answer opened nothing else: going straight from one
	 * menu to the next keeps the player's cursor where it was.
	 */
	private static void decide(MenuClick click, boolean[] decided, Runnable answer) {
		decided[0] = true;
		answer.run();
		if (!(click.menu() instanceof AbstractContainerMenu menu) || click.player().containerMenu == menu) {
			click.menu().close();
		}
	}

	public static void confirm(ServerPlayer player, Component title, List<Component> question, Runnable onConfirm) {
		confirm(player, title, question, onConfirm, () -> {
		});
	}
}
