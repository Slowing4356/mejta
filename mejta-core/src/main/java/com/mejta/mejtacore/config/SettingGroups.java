package com.mejta.mejtacore.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.config.Setting;

/**
 * How the settings menus group a large config: by the part of each key before the first dot
 * ({@code homes.max} is in "Homes"); keys without a dot are in "General". Shared by the server's chest
 * menu and the client menu.
 */
public final class SettingGroups {
	/** Configs with more settings than this, in two groups or more, open on their groups. */
	public static final int GROUP_ABOVE = 18;
	public static final String GENERAL = "general";

	private SettingGroups() {
	}

	/** Settings by group, in the config's order. */
	public static Map<String, List<Setting<?>>> of(List<Setting<?>> settings) {
		Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
		for (Setting<?> setting : settings) {
			groups.computeIfAbsent(groupOf(setting.key()), k -> new ArrayList<>()).add(setting);
		}
		return groups;
	}

	public static String groupOf(String key) {
		int dot = key.indexOf('.');
		return dot > 0 ? key.substring(0, dot) : GENERAL;
	}

	/** Whether a config with this many settings and groups opens on its groups. */
	public static boolean grouped(int settings, int groups) {
		return settings > GROUP_ABOVE && groups >= 2;
	}

	public static boolean grouped(List<Setting<?>> settings) {
		return grouped(settings.size(), of(settings).size());
	}

	/** {@code homes} becomes "Homes", {@code public_chat} "Public chat". */
	public static String displayName(String group) {
		String words = group.replace('_', ' ');
		return words.isEmpty() ? words : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}

	public static Item icon(String group) {
		return switch (group) {
			case GENERAL, "enabled" -> Items.COMPARATOR;
			case "teleport" -> Items.ENDER_EYE;
			case "tpa" -> Items.ENDER_PEARL;
			case "homes", "home" -> Items.RED_BED;
			case "back" -> Items.RECOVERY_COMPASS;
			case "spawn" -> Items.COMPASS;
			case "warps", "warp" -> Items.LODESTONE;
			case "rtp" -> Items.GRASS_BLOCK;
			case "stations" -> Items.CRAFTING_TABLE;
			case "afk" -> Items.CLOCK;
			case "chat", "messages", "welcome" -> Items.WRITABLE_BOOK;
			case "combat", "pvp" -> Items.IRON_SWORD;
			case "alerts", "notify", "notifications" -> Items.BELL;
			case "display", "scoreboard" -> Items.PAINTING;
			case "commands" -> Items.COMMAND_BLOCK;
			case "drops", "items" -> Items.CHEST;
			case "xp" -> Items.EXPERIENCE_BOTTLE;
			case "villagers" -> Items.EMERALD;
			case "sleep" -> Items.WHITE_BED;
			case "dimensions" -> Items.OBSIDIAN;
			case "antialt" -> Items.SHIELD;
			default -> Items.BOOK;
		};
	}
}
