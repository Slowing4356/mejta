package com.mejta.mejtautils.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import com.mejta.mejtacore.api.menu.MenuIcons;

/** Icons describing a player's current state, for /invsee's info row and the status panel. */
public final class PlayerInfo {
	private PlayerInfo() {
	}

	public static ItemStack head(ServerPlayer p, boolean online) {
		return head(p, online, p.level().dimension().identifier().toString());
	}

	/** @param dimension shown as the player's dimension (for offline snapshots: the saved one) */
	public static ItemStack head(ServerPlayer p, boolean online, String dimension) {
		List<Component> lore = new ArrayList<>();
		lore.add(MenuIcons.line("UUID: " + p.getUUID()));
		lore.add(online ? Component.literal("Online").withStyle(ChatFormatting.GREEN)
				: Component.literal("Offline (saved data)").withStyle(ChatFormatting.GOLD));
		if (online) {
			lore.add(MenuIcons.line("Ping: " + p.connection.latency() + " ms"));
		}
		lore.add(MenuIcons.line("Dimension: " + dimension));
		lore.add(MenuIcons.line(String.format(Locale.ROOT, "Position: %.1f, %.1f, %.1f", p.getX(), p.getY(), p.getZ())));
		lore.add(MenuIcons.line("Game mode: " + gameMode(p).getName()));
		return MenuIcons.head(p.getGameProfile(), Component.literal(p.getGameProfile().name()).withStyle(ChatFormatting.YELLOW), lore);
	}

	public static ItemStack health(ServerPlayer p) {
		List<Component> lore = new ArrayList<>();
		lore.add(MenuIcons.line(String.format(Locale.ROOT, "%.1f / %.1f (%.1f hearts)", p.getHealth(), p.getMaxHealth(), p.getHealth() / 2)));
		if (p.getAbsorptionAmount() > 0) {
			lore.add(MenuIcons.line(String.format(Locale.ROOT, "Absorption: %.1f", p.getAbsorptionAmount())));
		}
		return MenuIcons.of(Items.RED_DYE, Component.literal("Health").withStyle(ChatFormatting.RED), lore);
	}

	public static ItemStack food(ServerPlayer p) {
		return MenuIcons.of(Items.COOKED_BEEF, Component.literal("Hunger").withStyle(ChatFormatting.GOLD),
				MenuIcons.line("Food: " + p.getFoodData().getFoodLevel() + " / 20"),
				MenuIcons.line(String.format(Locale.ROOT, "Saturation: %.1f", p.getFoodData().getSaturationLevel())));
	}

	public static ItemStack experience(ServerPlayer p) {
		return MenuIcons.of(Items.EXPERIENCE_BOTTLE, Component.literal("Experience").withStyle(ChatFormatting.GREEN),
				MenuIcons.line("Level: " + p.experienceLevel),
				MenuIcons.line(String.format(Locale.ROOT, "Progress: %d%%", Math.round(p.experienceProgress * 100))),
				MenuIcons.line("Total points: " + p.totalExperience));
	}

	public static ItemStack effects(ServerPlayer p, List<Component> extra) {
		List<Component> lore = new ArrayList<>();
		if (p.getActiveEffects().isEmpty()) {
			lore.add(MenuIcons.line("No active effects"));
		}
		for (MobEffectInstance effect : p.getActiveEffects()) {
			String time = effect.isInfiniteDuration() ? "infinite" : formatTicks(effect.getDuration());
			lore.add(Component.empty().append(effect.getEffect().value().getDisplayName())
					.append(Component.literal(" " + roman(effect.getAmplifier() + 1) + "  " + time).withStyle(ChatFormatting.GRAY)));
		}
		lore.addAll(extra);
		return MenuIcons.of(Items.POTION, Component.literal("Active effects (" + p.getActiveEffects().size() + ")")
				.withStyle(ChatFormatting.LIGHT_PURPLE), lore);
	}

	public static ItemStack gameModeIcon(ServerPlayer p, List<Component> extra) {
		List<Component> lore = new ArrayList<>();
		lore.add(MenuIcons.line("Current: " + gameMode(p).getName()));
		lore.addAll(extra);
		return MenuIcons.of(Items.GRASS_BLOCK, Component.literal("Game mode").withStyle(ChatFormatting.AQUA), lore);
	}

	public static GameType gameMode(ServerPlayer p) {
		return p.gameMode.getGameModeForPlayer();
	}

	static String formatTicks(int ticks) {
		int seconds = ticks / 20;
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(n);
		};
	}
}
