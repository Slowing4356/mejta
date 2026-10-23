package com.mejta.mejtamarket.logic;

import java.util.Locale;
import java.util.Set;

/**
 * The market's item categories, worked out from an item's ID and a few of its properties, so the same
 * rules apply to modded items. Pure: the game-facing code passes in what it knows about the item.
 */
public final class Categories {
	public enum Category {
		ALL("All items", "minecraft:chest"),
		BLOCKS("Blocks", "minecraft:bricks"),
		ORES("Ores and minerals", "minecraft:diamond"),
		FARMING("Farming and food", "minecraft:wheat"),
		MOB_DROPS("Mob drops", "minecraft:bone"),
		TOOLS("Tools and weapons", "minecraft:iron_pickaxe"),
		ARMOR("Armour", "minecraft:iron_chestplate"),
		REDSTONE("Redstone", "minecraft:redstone"),
		MAGIC("Potions and enchanting", "minecraft:enchanted_book"),
		OTHER("Other", "minecraft:paper");

		private final String title;
		private final String icon;

		Category(String title, String icon) {
			this.title = title;
			this.icon = icon;
		}

		public String title() {
			return title;
		}

		/** Item ID of the category's icon. */
		public String icon() {
			return icon;
		}

		public Category next() {
			Category[] all = values();
			return all[(ordinal() + 1) % all.length];
		}

		public Category previous() {
			Category[] all = values();
			return all[(ordinal() + all.length - 1) % all.length];
		}
	}

	/**
	 * What the game knows about an item.
	 *
	 * @param id         e.g. {@code minecraft:iron_ingot}
	 * @param block      it places a block
	 * @param food       it can be eaten
	 * @param armor      it is worn (helmet, chestplate, ..., elytra, horse armour)
	 * @param toolOrWeapon it is a tool, a weapon or a bow
	 */
	public record ItemFacts(String id, boolean block, boolean food, boolean armor, boolean toolOrWeapon) {
	}

	private static final Set<String> ORE_WORDS = Set.of("ore", "ingot", "nugget", "raw_", "diamond", "emerald", "lapis", "quartz",
			"amethyst_shard", "coal", "netherite_scrap", "ancient_debris", "copper", "gold", "iron");
	private static final Set<String> REDSTONE_WORDS = Set.of("redstone", "repeater", "comparator", "piston", "observer", "hopper",
			"dropper", "dispenser", "lever", "button", "pressure_plate", "tripwire", "daylight_detector", "target", "rail", "crafter", "sculk_sensor");
	private static final Set<String> MOB_WORDS = Set.of("bone", "rotten_flesh", "string", "spider_eye", "gunpowder", "ender_pearl", "blaze_rod",
			"blaze_powder", "slime_ball", "ghast_tear", "phantom_membrane", "leather", "feather", "ink_sac", "magma_cream", "shulker_shell",
			"rabbit_hide", "breeze_rod", "armadillo_scute", "turtle_scute", "prismarine_shard", "prismarine_crystals", "nether_star", "head", "skull");
	private static final Set<String> FARM_WORDS = Set.of("seeds", "wheat", "sugar_cane", "bamboo", "cactus", "melon", "pumpkin", "carrot",
			"potato", "beetroot", "cocoa", "nether_wart", "kelp", "sapling", "egg", "bone_meal", "sweet_berries", "glow_berries", "honey");
	private static final Set<String> MAGIC_WORDS = Set.of("potion", "enchanted_book", "experience_bottle", "brewing", "glistering", "fermented",
			"dragon_breath", "totem", "book");

	private Categories() {
	}

	/** The one category an item is listed under (never {@link Category#ALL}). */
	public static Category of(ItemFacts item) {
		String path = path(item.id());
		if (path.endsWith("_spawn_egg")) {
			return Category.OTHER;
		}
		if (item.armor()) {
			return Category.ARMOR;
		}
		if (item.toolOrWeapon()) {
			return Category.TOOLS;
		}
		if (containsAny(path, MAGIC_WORDS) && !path.endsWith("_bookshelf") && !path.equals("bookshelf")) {
			return Category.MAGIC;
		}
		if (containsAny(path, REDSTONE_WORDS)) {
			return Category.REDSTONE;
		}
		if (item.food() || containsAny(path, FARM_WORDS)) {
			return Category.FARMING;
		}
		if (containsAny(path, MOB_WORDS)) {
			return Category.MOB_DROPS;
		}
		if (containsAny(path, ORE_WORDS) && !(item.block() && !path.endsWith("_ore") && !path.endsWith("_block") && !path.equals("ancient_debris"))) {
			return Category.ORES;
		}
		if (item.block()) {
			return Category.BLOCKS;
		}
		return Category.OTHER;
	}

	public static boolean matches(Category filter, ItemFacts item) {
		return filter == Category.ALL || of(item) == filter;
	}

	private static String path(String id) {
		int colon = id.indexOf(':');
		return (colon >= 0 ? id.substring(colon + 1) : id).toLowerCase(Locale.ROOT);
	}

	private static boolean containsAny(String path, Set<String> words) {
		for (String w : words) {
			if (path.contains(w)) {
				return true;
			}
		}
		return false;
	}
}
