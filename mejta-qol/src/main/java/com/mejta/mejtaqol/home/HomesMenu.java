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
import com.mejta.mejtacore.api.menu.PagedMenu;
import com.mejta.mejtacore.api.menu.ServerMenus;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.util.Cmds;

/**
 * {@code /homes}: one icon per home, over as many pages as needed. Left-click teleports, shift-right-click deletes
 * (after confirming). Staff with {@code mejtaqol.homes.others} can open another player's homes the same way.
 */
public final class HomesMenu {
	private HomesMenu() {
	}

	/** One home, with its place in the list (to reopen on the same page). */
	private record Home(int index, String name, Location loc) {
	}

	public static void open(ServerPlayer viewer, UUID owner, String ownerName, boolean staff) {
		open(viewer, owner, ownerName, staff, 0);
	}

	private static void open(ServerPlayer viewer, UUID owner, String ownerName, boolean staff, int page) {
		HomeBook book = MejtaQoL.homes().book(owner);
		String title = staff ? ownerName + "'s homes" : "Your homes";
		int rows = rows(book.size());
		PagedMenu.<Home>builder(Component.literal(title + " (" + book.size() + ")"), () -> homes(owner))
				.rows(rows)
				.entry(home -> MenuIcons.of(icon(home.loc()), Component.literal(home.name()).withStyle(ChatFormatting.AQUA),
						MenuIcons.line(home.loc().describe()),
						Component.empty(),
						Component.literal("Left-click: teleport").withStyle(ChatFormatting.YELLOW),
						Component.literal("Shift-right-click: delete").withStyle(ChatFormatting.RED)),
						(home, click) -> clicked(click, owner, ownerName, staff, home, (rows - 1) * 9))
				.empty(() -> MenuIcons.of(Items.RED_BED, Component.literal("No homes yet").withStyle(ChatFormatting.GRAY),
						MenuIcons.line("Stand somewhere and use /sethome [name]")))
				.info(() -> {
					List<Component> lore = new ArrayList<>();
					if (!staff) {
						lore.add(MenuIcons.line(MejtaQoL.homes().book(owner).size() + " of " + HomeCommands.maxHomes(viewer) + " homes used"));
					}
					lore.add(MenuIcons.line("Set a home with /sethome [name]"));
					return MenuIcons.of(Items.BOOK, Component.literal(title).withStyle(ChatFormatting.GOLD), lore);
				})
				.validWhile(p -> staff ? MejtaCoreAPI.hasPermission(p, QolPermissions.HOMES_OTHERS)
						: Cmds.available(Features.HOME).test(p.createCommandSourceStack()))
				.open(viewer, page);
	}

	/** Just enough rows for the homes (plus the bar), so a few homes do not sit in a big empty chest. */
	static int rows(int homes) {
		return Math.max(2, Math.min(6, (homes + 8) / 9 + 1));
	}

	private static List<Home> homes(UUID owner) {
		List<Home> homes = new ArrayList<>();
		for (Map.Entry<String, Location> e : MejtaQoL.homes().book(owner).all().entrySet()) {
			homes.add(new Home(homes.size(), e.getKey(), e.getValue()));
		}
		return homes;
	}

	private static void clicked(MenuClick click, UUID owner, String ownerName, boolean staff, Home home, int perPage) {
		ServerPlayer player = click.player();
		String name = home.name();
		int page = home.index() / perPage;
		if (click.type() == MenuClick.Type.SHIFT_RIGHT) {
			ServerMenus.confirm(player, Component.literal("Delete home " + name + "?"),
					List.of(MenuIcons.line(staff ? "Deletes " + ownerName + "'s home " + name : "Deletes your home " + name)),
					() -> {
						if (staff) {
							HomeCommands.deleteOther(player, new NameAndId(owner, ownerName), name);
						} else {
							HomeCommands.deleteOwn(player, name);
						}
						open(player, owner, ownerName, staff, page);
					},
					() -> open(player, owner, ownerName, staff, page));
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
