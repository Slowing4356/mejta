package com.mejta.mejtacrates;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.data.Roll;
import com.mejta.mejtacrates.util.ItemJson;

/** Names, icons and descriptions of crates and rewards, shared by menus, commands and messages. */
public final class CrateTexts {
	private CrateTexts() {
	}

	public static ChatFormatting color(Crate crate) {
		ChatFormatting color = ChatFormatting.getByName(crate.color());
		return color != null && color.isColor() ? color : ChatFormatting.GOLD;
	}

	public static MutableComponent name(Crate crate) {
		return Component.literal(crate.name()).withStyle(color(crate));
	}

	public static ItemStack crateIcon(HolderLookup.Provider registries, Crate crate) {
		return crate.icon().flatMap(json -> ItemJson.decode(registries, json)).filter(s -> !s.isEmpty())
				.orElseGet(() -> new ItemStack(Items.CHEST));
	}

	/** The stack shown for a reward: its display item, else its first item, else gold for money or paper. */
	public static ItemStack rewardIcon(HolderLookup.Provider registries, Reward reward) {
		Optional<ItemStack> shown = reward.display().flatMap(json -> ItemJson.decode(registries, json));
		if (shown.isEmpty() && !reward.items().isEmpty()) {
			shown = ItemJson.decode(registries, reward.items().get(0));
		}
		return shown.filter(s -> !s.isEmpty())
				.orElseGet(() -> new ItemStack(reward.paysMoney() ? Items.GOLD_INGOT : reward.commands().isEmpty() ? Items.BARRIER : Items.PAPER));
	}

	/** The reward's name for players, e.g. {@code 3x Diamond} or {@code $100.00}. */
	public static String rewardName(HolderLookup.Provider registries, Reward reward) {
		if (!reward.name().isEmpty()) {
			return reward.name();
		}
		if (!reward.items().isEmpty()) {
			Optional<ItemStack> first = ItemJson.decode(registries, reward.items().get(0));
			if (first.isPresent()) {
				ItemStack s = first.get();
				String text = (s.getCount() > 1 ? s.getCount() + "x " : "") + s.getHoverName().getString();
				return reward.items().size() > 1 ? text + " and more" : text;
			}
		}
		if (reward.paysMoney()) {
			return MejtaCoreAPI.economy().format(reward.money());
		}
		return reward.commands().isEmpty() ? "Nothing" : "A surprise";
	}

	/** Lore lines describing what a reward gives. */
	public static List<Component> rewardLines(HolderLookup.Provider registries, Reward reward) {
		List<Component> lines = new ArrayList<>();
		for (var json : reward.items()) {
			ItemJson.decode(registries, json).ifPresent(s -> lines.add(MenuIcons.line("• " + s.getCount() + "x " + s.getHoverName().getString())));
		}
		if (reward.paysMoney()) {
			lines.add(MenuIcons.line("• " + MejtaCoreAPI.economy().format(reward.money())));
		}
		if (!reward.commands().isEmpty() && reward.items().isEmpty() && !reward.paysMoney()) {
			lines.add(MenuIcons.line("• " + (reward.name().isEmpty() ? "A special reward" : reward.name())));
		}
		return lines;
	}

	/** The icon of a reward with its chance and contents, for previews and the animation. */
	public static ItemStack rewardCard(HolderLookup.Provider registries, Reward reward, List<Reward> pool, boolean withChance) {
		List<Component> lore = new ArrayList<>(rewardLines(registries, reward));
		if (withChance) {
			lore.add(Component.literal("Chance: " + Roll.percent(Roll.chance(reward, pool))).withStyle(ChatFormatting.YELLOW));
		}
		return MenuIcons.of(rewardIcon(registries, reward), Component.literal(rewardName(registries, reward)).withStyle(ChatFormatting.AQUA), lore);
	}
}
