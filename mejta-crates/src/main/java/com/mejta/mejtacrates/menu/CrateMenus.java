package com.mejta.mejtacrates.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.menu.ServerMenus;
import com.mejta.mejtacrates.CratePermissions;
import com.mejta.mejtacrates.CrateService;
import com.mejta.mejtacrates.CrateSettings;
import com.mejta.mejtacrates.CrateTexts;
import com.mejta.mejtacrates.MejtaCrates;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.PlayerKeys;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.data.Roll;

/**
 * {@code /crates}: every crate with the player's keys, and a crate's preview with each reward's chance. Server
 * menus, so they show as MejtaCore screens or as chests.
 */
public final class CrateMenus {
	private static final int PAGE = 45;

	private CrateMenus() {
	}

	/** The crate list: left-click opens a crate with a key, right-click previews it. */
	public static void list(ServerPlayer player) {
		CrateService service = MejtaCrates.service();
		HolderLookup.Provider registries = player.level().getServer().registryAccess();
		List<Crate> crates = service.store().crates();
		int rows = Math.max(2, Math.min(6, (crates.size() + 8) / 9 + 1));
		int bar = (rows - 1) * 9;
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Crates"), rows);
		if (crates.isEmpty()) {
			menu.icon(4, () -> MenuIcons.of(Items.BARRIER, Component.literal("There are no crates yet").withStyle(ChatFormatting.GRAY)));
		}
		menu.icon(bar + 4, () -> MenuIcons.of(Items.BOOK, Component.literal("Crates").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Left-click a crate to open it with a key"), MenuIcons.line("Right-click to see what it can give")));
		menu.closeButton(bar + 8);
		for (int i = 0; i < crates.size() && i < bar; i++) {
			Crate crate = crates.get(i);
			menu.button(i, () -> {
				int keys = service.store().player(player.getUUID(), player.getGameProfile().name()).keys(crate.id());
				List<Component> lore = new ArrayList<>();
				lore.add(Component.literal("Your keys: " + keys).withStyle(keys > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
				lore.add(MenuIcons.line(CrateService.pool(crate).size() + " possible rewards"));
				if (keys > 0 && MejtaCrates.config().get(CrateSettings.OPEN_FROM_MENU)) {
					lore.add(Component.literal("Left-click to open").withStyle(ChatFormatting.YELLOW));
				}
				lore.add(Component.literal("Right-click to see the rewards").withStyle(ChatFormatting.YELLOW));
				ItemStack icon = CrateTexts.crateIcon(registries, crate);
				icon.setCount(Math.max(1, Math.min(keys, icon.getMaxStackSize())));
				return MenuIcons.of(icon, CrateTexts.name(crate), lore);
			}, click -> {
				Crate current = service.store().crate(crate.id()).orElse(null);
				if (current == null) {
					click.menu().close();
					return;
				}
				if (click.type().isRight()) {
					preview(click.player(), current, 0, true);
				} else if (!MejtaCrates.config().get(CrateSettings.OPEN_FROM_MENU)) {
					click.player().sendSystemMessage(Component.literal("Open crates at their crate block.").withStyle(ChatFormatting.GRAY));
				} else {
					// No close first: the spin replaces this menu (keeping the cursor), or the menu stays with the new key count.
					tell(click.player(), current, service.openVirtual(click.player(), current, true));
				}
			});
		}
		menu.fillEmpty().refreshEvery(20).validWhile(p -> MejtaCoreAPI.hasPermission(p, CratePermissions.USE)).open(player);
	}

	/** A crate's rewards with their chances; admins remove a reward with shift-right-click. */
	public static void preview(ServerPlayer player, Crate crate, int page, boolean backToList) {
		CrateService service = MejtaCrates.service();
		HolderLookup.Provider registries = player.level().getServer().registryAccess();
		boolean admin = MejtaCoreAPI.hasPermission(player, CratePermissions.ADMIN);
		// Admins see every reward (also money rewards without an economy), numbered as in /crates admin info.
		List<Reward> pool = CrateService.pool(crate);
		List<Reward> shown = admin ? crate.rewards() : pool;
		int pages = Math.max(1, (shown.size() + PAGE - 1) / PAGE);
		int current = Math.max(0, Math.min(page, pages - 1));
		int count = Math.min(PAGE, shown.size() - current * PAGE);
		int rows = Math.min(6, (Math.max(count, 1) + 8) / 9 + 1);
		int bar = (rows - 1) * 9;
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal("Rewards: ").append(CrateTexts.name(crate)), rows);
		for (int i = 0; i < count; i++) {
			int index = current * PAGE + i;
			Reward reward = shown.get(index);
			boolean drawable = pool.contains(reward);
			List<Component> extra = new ArrayList<>();
			if (admin) {
				extra.add(Component.literal("#" + (index + 1) + "  weight " + reward.weight()).withStyle(ChatFormatting.DARK_GRAY));
				if (!drawable) {
					extra.add(Component.literal("Not drawn: no economy is installed").withStyle(ChatFormatting.RED));
				}
				extra.add(Component.literal("Shift-right-click to remove").withStyle(ChatFormatting.DARK_GRAY));
			}
			ItemStack card = CrateTexts.rewardCard(registries, reward, pool, drawable);
			menu.button(i, () -> withLore(card, extra), click -> {
				if (admin && click.type() == com.mejta.mejtacore.api.menu.MenuClick.Type.SHIFT_RIGHT
						&& MejtaCoreAPI.hasPermission(click.player(), CratePermissions.ADMIN)) {
					confirmRemove(click.player(), crate.id(), index, reward);
				}
			});
		}
		if (shown.isEmpty()) {
			menu.icon(4, () -> MenuIcons.of(Items.BARRIER, Component.literal("This crate has no rewards yet").withStyle(ChatFormatting.GRAY)));
		}
		if (backToList) {
			menu.backButton(bar, "crates", CrateMenus::list);
		}
		if (current > 0) {
			menu.button(bar + 3, () -> MenuIcons.page(false, current, pages),
					click -> preview(click.player(), crate, current - 1, backToList));
		}
		menu.button(bar + 4, () -> {
			int keys = service.store().player(player.getUUID(), player.getGameProfile().name()).keys(crate.id());
			boolean canOpen = keys > 0 && MejtaCrates.config().get(CrateSettings.OPEN_FROM_MENU);
			return MenuIcons.of(canOpen ? Items.TRIPWIRE_HOOK : Items.CHEST, CrateTexts.name(crate),
					List.of(MenuIcons.line("Your keys: " + keys), MenuIcons.line("Page " + (current + 1) + " of " + pages),
							Component.literal(canOpen ? "Click to open" : "Get keys from the server's store or events")
									.withStyle(canOpen ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY)));
		}, click -> {
			if (MejtaCrates.config().get(CrateSettings.OPEN_FROM_MENU)) {
				service.store().crate(crate.id()).ifPresent(c -> {
					tell(click.player(), c, service.openVirtual(click.player(), c, true));
				});
			}
		});
		if (current < pages - 1) {
			menu.button(bar + 5, () -> MenuIcons.page(true, current + 2, pages),
					click -> preview(click.player(), crate, current + 1, backToList));
		}
		menu.closeButton(bar + 8).fillEmpty().refreshEvery(20).validWhile(p -> MejtaCoreAPI.hasPermission(p, CratePermissions.USE)).open(player);
	}

	private static void confirmRemove(ServerPlayer player, String crateId, int index, Reward reward) {
		String name = CrateTexts.rewardName(player.level().getServer().registryAccess(), reward);
		ServerMenus.confirm(player, Component.literal("Remove reward #" + (index + 1) + "?"), List.of(MenuIcons.line(name)), () -> {
			if (!MejtaCoreAPI.hasPermission(player, CratePermissions.ADMIN)) {
				return;
			}
			MejtaCrates.service().store().crate(crateId).ifPresent(crate -> {
				// Only if the crate was not edited in between.
				if (index < crate.rewards().size() && crate.rewards().get(index).equals(reward)) {
					com.mejta.mejtacrates.CrateEditor.removeReward(MejtaCoreAPI.actor(player), player::sendSystemMessage, crate, index);
				} else {
					player.sendSystemMessage(Component.literal("The crate changed in the meantime; nothing was removed.").withStyle(ChatFormatting.RED));
				}
				MejtaCrates.service().store().crate(crateId).ifPresent(c -> preview(player, c, index / PAGE, false));
			});
		}, () -> MejtaCrates.service().store().crate(crateId).ifPresent(c -> preview(player, c, index / PAGE, false)));
	}

	private static ItemStack withLore(ItemStack card, List<Component> extra) {
		if (extra.isEmpty()) {
			return card.copy();
		}
		List<Component> lore = new ArrayList<>();
		var existing = card.get(net.minecraft.core.component.DataComponents.LORE);
		if (existing != null) {
			lore.addAll(existing.lines());
		}
		lore.addAll(extra);
		return MenuIcons.of(card, card.getHoverName(), lore);
	}

	/** Tells the player why an opening did not start. */
	public static void tell(ServerPlayer player, Crate crate, CrateService.OpenOutcome outcome) {
		String text = switch (outcome) {
			case STARTED -> null;
			case NO_KEY -> "You have no " + crate.name() + " key.";
			case NO_REWARDS -> crate.name() + " has no rewards yet.";
			case BUSY -> "Wait until your current crate has finished.";
			case INVALID_KEY, COPIED_KEY -> null; // already told
			case FAILED -> "The crate could not be opened (a storage error). Your key was not used.";
		};
		if (text != null) {
			player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED));
		}
	}
}
