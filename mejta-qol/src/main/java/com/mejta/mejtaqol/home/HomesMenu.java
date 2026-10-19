package com.mejta.mejtaqol.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.menu.ServerMenus;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.util.Cmds;

/**
 * {@code /homes}: one icon per home. Left-click teleports, shift-right-click deletes (after confirming).
 * Staff with {@code mejtaqol.homes.others} can open another player's homes the same way.
 */
public final class HomesMenu {
	/** Homes shown; more are reachable with {@code /home <name>}. */
	static final int MAX_SHOWN = 45;

	private HomesMenu() {
	}

	public static void open(ServerPlayer viewer, UUID owner, String ownerName, boolean staff) {
		HomeBook book = MejtaQoL.homes().book(owner);
		List<Map.Entry<String, Location>> homes = new ArrayList<>(book.all().entrySet());
		int shown = Math.min(homes.size(), MAX_SHOWN);
		int rows = Math.max(1, (shown + 8) / 9) + 1;
		String title = staff ? ownerName + "'s homes" : "Your homes";
		ServerMenu.Builder menu = ServerMenu.builder(Component.literal(title + " (" + book.size() + ")"), rows);
		for (int i = 0; i < shown; i++) {
			String name = homes.get(i).getKey();
			Location loc = homes.get(i).getValue();
			menu.button(i, () -> MenuIcons.of(icon(loc), Component.literal(name).withStyle(ChatFormatting.AQUA),
					MenuIcons.line(loc.describe()),
					MenuIcons.line("Left-click: teleport"),
					MenuIcons.line("Shift-right-click: delete")),
					click -> clicked(click, owner, ownerName, staff, name));
		}
		int info = (rows - 1) * 9 + 4;
		List<Component> lore = new ArrayList<>();
		if (!staff) {
			lore.add(MenuIcons.line(book.size() + " of " + HomeCommands.maxHomes(viewer) + " homes used"));
		}
		if (homes.size() > MAX_SHOWN) {
			lore.add(MenuIcons.line((homes.size() - MAX_SHOWN) + " more: use /home <name>"));
		}
		lore.add(MenuIcons.line("Set a home with /sethome [name]"));
		menu.icon(info, () -> MenuIcons.of(Items.PAPER, Component.literal(title).withStyle(ChatFormatting.GOLD), lore));
		menu.validWhile(p -> staff ? MejtaCoreAPI.hasPermission(p, QolPermissions.HOMES_OTHERS)
				: Cmds.available(Features.HOME).test(p.createCommandSourceStack()));
		menu.open(viewer);
	}

	private static void clicked(MenuClick click, UUID owner, String ownerName, boolean staff, String name) {
		ServerPlayer player = click.player();
		if (click.type() == MenuClick.Type.SHIFT_RIGHT) {
			ServerMenus.confirm(player, Component.literal("Delete home " + name + "?"),
					List.of(MenuIcons.line(staff ? "Deletes " + ownerName + "'s home " + name : "Deletes your home " + name)),
					() -> {
						if (staff) {
							HomeCommands.deleteOther(player, new NameAndId(owner, ownerName), name);
						} else {
							HomeCommands.deleteOwn(player, name);
						}
						open(player, owner, ownerName, staff);
					},
					() -> open(player, owner, ownerName, staff));
			return;
		}
		if (click.type() != MenuClick.Type.LEFT) {
			return;
		}
		click.menu().close();
		if (!staff) {
			HomeCommands.home(player, Optional.of(name));
			return;
		}
		HomeCommands.visitOther(player, new NameAndId(owner, ownerName), name);
	}

	static Item icon(Location loc) {
		return switch (loc.dimension()) {
			case "minecraft:overworld" -> Items.GRASS_BLOCK;
			case "minecraft:the_nether" -> Items.NETHERRACK;
			case "minecraft:the_end" -> Items.END_STONE;
			default -> Items.COMPASS;
		};
	}
}
