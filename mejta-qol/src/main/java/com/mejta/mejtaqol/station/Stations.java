package com.mejta.mejtaqol.station;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Msg;

/**
 * Portable containers: the player's own ender chest, the vanilla workstations and a trash bin.
 *
 * <p>The workstations are the game's own menus, bound to the player's position so that items left in
 * their input slots are given back (or dropped) when the menu closes, exactly as at a real block. Only
 * the "is the block still there" check is removed. A portable anvil never takes damage, because no anvil
 * block is at that position.
 */
public final class Stations {
	private Stations() {
	}

	private record Station(String name, Setting<Boolean> enabled, MejtaPermission permission, String titleKey, MenuConstructor menu) {
	}

	private static final Station[] WORKSTATIONS = {
			new Station("workbench", QolSettings.WORKBENCH_ENABLED, QolPermissions.WORKBENCH, "container.crafting",
					(id, inv, p) -> new PortableCrafting(id, inv, here(p))),
			new Station("anvil", QolSettings.ANVIL_ENABLED, QolPermissions.ANVIL, "container.repair",
					(id, inv, p) -> new PortableAnvil(id, inv, here(p))),
			new Station("stonecutter", QolSettings.STONECUTTER_ENABLED, QolPermissions.STONECUTTER, "container.stonecutter",
					(id, inv, p) -> new PortableStonecutter(id, inv, here(p))),
			new Station("grindstone", QolSettings.GRINDSTONE_ENABLED, QolPermissions.GRINDSTONE, "container.grindstone_title",
					(id, inv, p) -> new PortableGrindstone(id, inv, here(p))),
			new Station("smithing", QolSettings.SMITHING_ENABLED, QolPermissions.SMITHING, "container.upgrade",
					(id, inv, p) -> new PortableSmithing(id, inv, here(p))),
			new Station("loom", QolSettings.LOOM_ENABLED, QolPermissions.LOOM, "container.loom",
					(id, inv, p) -> new PortableLoom(id, inv, here(p))),
			new Station("cartography", QolSettings.CARTOGRAPHY_ENABLED, QolPermissions.CARTOGRAPHY, "container.cartography_table",
					(id, inv, p) -> new PortableCartography(id, inv, here(p))),
	};

	public static void register() {
		String id = MejtaQoL.ID;
		for (Station s : WORKSTATIONS) {
			MejtaCoreAPI.registerRootCommand(id, s.name(), (root, ctx) -> root
					.requires(Cmds.available(s.enabled(), s.permission()))
					.executes(c -> open(c.getSource().getPlayerOrException(), s)));
		}
		// Short names for the crafting table.
		Station crafting = WORKSTATIONS[0];
		MejtaCoreAPI.registerRootCommand(id, "craft", (root, ctx) -> root
				.requires(Cmds.available(crafting.enabled(), crafting.permission()))
				.executes(c -> open(c.getSource().getPlayerOrException(), crafting)));
		for (String name : new String[] {"enderchest", "ec"}) {
			MejtaCoreAPI.registerRootCommand(id, name, (root, ctx) -> root
					.requires(Cmds.available(QolSettings.ENDERCHEST_ENABLED, QolPermissions.ENDERCHEST))
					.executes(c -> openEnderChest(c.getSource().getPlayerOrException())));
		}
		MejtaCoreAPI.registerRootCommand(id, "trash", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.TRASH_ENABLED, QolPermissions.TRASH))
				.executes(c -> openTrash(c.getSource().getPlayerOrException())));
	}

	private static ContainerLevelAccess here(Player player) {
		return ContainerLevelAccess.create(player.level(), player.blockPosition());
	}

	private static int open(ServerPlayer player, Station s) {
		player.openMenu(new SimpleMenuProvider(s.menu(), Component.translatable(s.titleKey())));
		return 1;
	}

	/** Opens the player's own ender chest, as if they had opened an ender chest block. */
	public static int openEnderChest(ServerPlayer player) {
		PlayerEnderChestContainer chest = player.getEnderChestInventory();
		// No ender chest block is involved: forget any block the container was last opened from.
		chest.setActiveChest(null);
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> chest.getContainerSize() >= 54
				? ChestMenu.sixRows(id, inv, chest) : ChestMenu.threeRows(id, inv, chest), Component.translatable("container.enderchest")));
		player.awardStat(Stats.OPEN_ENDERCHEST);
		return 1;
	}

	/** A 3-row chest whose contents are deleted when it closes. */
	public static int openTrash(ServerPlayer player) {
		SimpleContainer bin = new SimpleContainer(27);
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, bin), Component.literal("Trash")));
		player.sendSystemMessage(Msg.info("Anything left in the trash is deleted when you close it."));
		return 1;
	}

	// The game's menus without the "is the block still there" check.

	public static final class PortableCrafting extends CraftingMenu {
		public PortableCrafting(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableAnvil extends AnvilMenu {
		public PortableAnvil(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableStonecutter extends StonecutterMenu {
		public PortableStonecutter(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableGrindstone extends GrindstoneMenu {
		public PortableGrindstone(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableSmithing extends SmithingMenu {
		public PortableSmithing(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableLoom extends LoomMenu {
		public PortableLoom(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	public static final class PortableCartography extends CartographyTableMenu {
		public PortableCartography(int id, Inventory inv, ContainerLevelAccess access) {
			super(id, inv, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}
}
