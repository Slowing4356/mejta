package com.mejta.mejtacrates.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.menu.MenuIcons;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.api.server.Scheduler;
import com.mejta.mejtacrates.CrateService;
import com.mejta.mejtacrates.CrateTexts;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.PlayerKeys.Opening;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.data.Roll;

/**
 * The opening of a crate: rewards spin through the middle row of a 3-row server menu and slow down until
 * the drawn one stops under the hopper. A MejtaCore screen for players with MejtaCore on their client, a chest
 * for everyone else.
 *
 * <p>The prize was drawn and saved as a pending opening before this starts, so the animation only shows it.
 * Closing the menu early, or the "Skip" button, hands it out at once; a disconnect leaves it pending for the
 * next join.
 */
public final class OpeningAnimation {
	private static final int CENTER = 13;
	private static final int SKIP = 22;
	private static final int CLOSE_AFTER_TICKS = 60;
	private static final Item[] SPIN_PANES = {Items.MAGENTA_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
			Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE, Items.PINK_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE};

	private final CrateService service;
	private final ServerPlayer player;
	private final Crate crate;
	private final Opening opening;
	private final boolean sounds;
	private final List<ItemStack> reel = new ArrayList<>();
	private final int[] stepTicks;
	private final ItemStack winnerCard;

	private int tick;
	private int step;
	private int doneTick = -1;
	private boolean closeRequested;
	private boolean ended;
	private Scheduler.Task task;

	public OpeningAnimation(CrateService service, ServerPlayer player, Crate crate, List<Reward> pool, Opening opening, int ticks, boolean sounds,
			RandomGenerator random) {
		this.service = service;
		this.player = player;
		this.crate = crate;
		this.opening = opening;
		this.sounds = sounds;
		this.stepTicks = stepTicks(ticks);
		HolderLookup.Provider registries = player.level().getServer().registryAccess();
		List<ItemStack> cards = new ArrayList<>();
		for (Reward r : pool) {
			cards.add(CrateTexts.rewardCard(registries, r, pool, false));
		}
		int winnerAt = stepTicks.length + 4;
		for (int i = 0; i < stepTicks.length + 9; i++) {
			reel.add(i == winnerAt ? CrateTexts.rewardCard(registries, opening.reward(), pool, false) : cards.get(Roll.pick(pool, random)));
		}
		List<Component> lore = new ArrayList<>(CrateTexts.rewardLines(registries, opening.reward()));
		lore.add(Component.literal("You won this!").withStyle(ChatFormatting.GREEN));
		this.winnerCard = MenuIcons.of(CrateTexts.rewardIcon(registries, opening.reward()),
				Component.literal(CrateTexts.rewardName(registries, opening.reward())).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), lore);
	}

	/** Tick of each step of the spin: fast at first, slower towards the end. */
	static int[] stepTicks(int ticks) {
		List<Integer> times = new ArrayList<>();
		int t = 0;
		while (t < ticks) {
			double progress = (double) t / ticks;
			t += 1 + (int) (progress * progress * 8);
			times.add(t);
		}
		return times.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Opens the menu and starts spinning; {@code false} if the menu could not be opened. */
	public boolean open() {
		ServerMenu.Builder menu = ServerMenu.builder(CrateTexts.name(crate), 3);
		for (int slot = 0; slot < 27; slot++) {
			if (slot >= 9 && slot < 18) {
				int column = slot - 9;
				menu.icon(slot, () -> reelIcon(column));
			} else if (slot == 4) {
				menu.icon(slot, () -> MenuIcons.of(Items.HOPPER, Component.literal(done() ? "Your prize" : "Spinning...").withStyle(ChatFormatting.GOLD)));
			} else if (slot == SKIP) {
				menu.button(slot, () -> done()
						? MenuIcons.of(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED))
						: MenuIcons.of(Items.FEATHER, Component.literal("Skip").withStyle(ChatFormatting.YELLOW), MenuIcons.line("Show the prize now")),
						click -> {
							if (done()) {
								click.menu().close();
							} else {
								complete();
							}
						});
			} else {
				int position = slot;
				menu.icon(slot, () -> pane(position));
			}
		}
		menu.refreshEvery(1)
				.validWhile(p -> !closeRequested)
				.onClose(this::closed);
		if (!menu.open(player)) {
			return false;
		}
		task = MejtaCoreAPI.scheduler().runEvery(1, this::tick);
		return true;
	}

	private boolean done() {
		return doneTick >= 0;
	}

	private ItemStack reelIcon(int column) {
		if (done() && column != 4) {
			return MenuIcons.of(Items.GRAY_STAINED_GLASS_PANE, Component.literal(" "));
		}
		if (done()) {
			return winnerCard.copy();
		}
		return reel.get(Math.min(step + column, reel.size() - 1)).copy();
	}

	private ItemStack pane(int slot) {
		Item item = done() ? Items.LIME_STAINED_GLASS_PANE : SPIN_PANES[Math.floorMod(slot + step, SPIN_PANES.length)];
		return MenuIcons.of(item, Component.literal(" "));
	}

	private void tick() {
		if (ended) {
			return;
		}
		if (player.hasDisconnected() || player.isRemoved()) {
			abandon();
			return;
		}
		tick++;
		if (!done()) {
			boolean moved = false;
			while (step < stepTicks.length && tick >= stepTicks[step]) {
				step++;
				moved = true;
			}
			if (moved) {
				sound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.5F + 1.5F * step / stepTicks.length);
			}
			if (step >= stepTicks.length) {
				complete();
			}
		} else if (tick - doneTick >= CLOSE_AFTER_TICKS) {
			closeRequested = true;
		}
	}

	/** Shows the prize and hands it out. */
	private void complete() {
		if (done()) {
			return;
		}
		step = stepTicks.length;
		doneTick = tick;
		service.finish(player, opening.id());
		sound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F);
		sound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5F);
	}

	private void closed(ServerPlayer who) {
		if (!done() && !who.hasDisconnected() && !who.isRemoved()) {
			service.finish(who, opening.id());
		}
		end();
	}

	/** Stops without handing out the prize: it stays pending for the next join. */
	public void abandon() {
		end();
	}

	private void end() {
		if (ended) {
			return;
		}
		ended = true;
		if (task != null) {
			task.cancel();
		}
		service.animationEnded(player, this);
	}

	private void sound(SoundEvent sound, float pitch) {
		if (sounds) {
			player.level().playSound((Player) null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.6F, pitch);
		}
	}
}
