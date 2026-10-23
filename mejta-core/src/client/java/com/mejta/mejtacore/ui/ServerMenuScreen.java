package com.mejta.mejtacore.ui;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import com.mejta.mejtacore.network.MenuPayloads.MenuSlot;
import com.mejta.mejtacore.network.MenuPayloads.OpenMenu;
import com.mejta.mejtacore.network.ServerMenuClient;

/**
 * A server's menu (homes, warps, leaderboards, staff panels...) shown as cards instead of a chest. The icons are
 * the server's, read in the chest's order; a click is sent to the server, which runs the button and redraws or
 * closes the menu. Right-click, shift-click and middle-click reach the server as they would from the chest.
 */
public class ServerMenuScreen extends CardScreen {
	private OpenMenu menu;
	/** Set when the server closed the menu, so closing the screen does not report it back. */
	private boolean closedByServer;

	public ServerMenuScreen(OpenMenu menu) {
		super(menu.title(), null);
		this.menu = menu;
	}

	public int session() {
		return menu.session();
	}

	/** The server redrew the menu (same session): new icons, same scroll position. */
	public void update(OpenMenu next) {
		menu = next;
		refresh();
	}

	/** The server closed this menu. */
	public void closeFromServer() {
		closedByServer = true;
		closeAll();
	}

	@Override
	protected List<Item> buildItems() {
		List<Item> items = new ArrayList<>();
		for (MenuSlot slot : menu.slots()) {
			ItemStack icon = slot.icon();
			ItemLore lore = icon.get(DataComponents.LORE);
			List<Component> lines = lore == null ? List.of() : lore.lines();
			int index = slot.slot();
			CardAction action = slot.clickable() ? click -> ServerMenuClient.click(menu.session(), index, click) : null;
			items.add(new Card("slot_" + index, icon, icon.getHoverName(), lines, action, icon.hasFoil()));
		}
		return items;
	}

	@Override
	protected List<FooterButton> footerButtons() {
		return List.of(new FooterButton("close", Component.literal("Close"), null, this::onClose));
	}

	@Override
	public void onClose() {
		closeAll();
	}

	@Override
	public void removed() {
		super.removed();
		if (!closedByServer) {
			ServerMenuClient.closed(menu.session());
		}
	}

	// ---------------------------------------------------------------- for tests and tooling

	/** ID of the card showing the icon whose name equals {@code name}, or {@code null}. */
	public @Nullable String cardNamed(String name) {
		for (MenuSlot slot : menu.slots()) {
			if (slot.icon().getHoverName().getString().equals(name)) {
				return "slot_" + slot.slot();
			}
		}
		return null;
	}
}
