package com.mejta.mejtasmp.drops;

import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.mixin.ItemEntityAccessor;

/**
 * Dropped items merge over a wider distance ({@code drops.merge_radius}) and, with
 * {@code drops.stack_beyond_max}, into piles bigger than a stack.
 *
 * <h2>How a pile is held, and why items are never duplicated</h2>
 * A pile is one ordinary item entity: its visible stack (at most a full stack), plus a count of further
 * items ({@link PileHolder#mejtasmp$extra()}) saved with the entity. Everything in the game that takes
 * items from an item entity (players, hoppers, mobs, allays) sees only the visible stack and takes from it
 * as usual. When it then removes the entity, {@link #discarded} hands the rest to a new item entity at the
 * same place, which shows the next stack. The count always moves (old set to 0, then the new one gets
 * it), never copies. An item entity destroyed with its items (lava, fire, explosions, the void,
 * despawning) has its count cleared first, so the whole pile is gone, as a stack would be. Merging moves
 * the smaller pile's whole count into the larger and clears the smaller before it is removed.
 *
 * <p>Server thread only. Without MejtaSMP's config (not initialized) everything is vanilla.
 */
public final class Piles {
	private static final int LABEL_EVERY = 10;
	private static final int NEVER_PICK_UP = 32767;
	private static final int UNLIMITED_LIFETIME = -32768;
	private static final int DESPAWN_AGE = 6000;
	/** How far up and down merging reaches, in blocks: enough for drops on stairs and slabs. */
	private static final double VERTICAL_REACH = 0.5;

	private Piles() {
	}

	/** Items in the pile: the visible stack plus the rest. */
	public static int total(ItemEntity entity) {
		return entity.getItem().getCount() + ((PileHolder) entity).mejtasmp$extra();
	}

	/** Whether the entity may merge now, or {@code null} for vanilla's rule. */
	public static Boolean mergable(ItemEntity entity) {
		ConfigHandle config = MejtaSMP.configOrNull();
		if (config == null || !(entity.level() instanceof ServerLevel)) {
			return null;
		}
		return eligible(entity, config.get(SmpSettings.DROPS_STACK), config.get(SmpSettings.DROPS_MAX_PILE));
	}

	/**
	 * Like vanilla, items that never stack (tools, armour, potions) never merge, so farms full of them cost
	 * no merge scans.
	 */
	static boolean eligible(ItemEntity entity, boolean piles, int cap) {
		ItemEntityAccessor access = (ItemEntityAccessor) entity;
		ItemStack stack = entity.getItem();
		if (!entity.isAlive() || stack.isEmpty() || stack.getMaxStackSize() <= 1 || access.mejtasmp$pickupDelay() == NEVER_PICK_UP
				|| access.mejtasmp$age() == UNLIMITED_LIFETIME || access.mejtasmp$age() >= DESPAWN_AGE) {
			return false;
		}
		return piles ? total(entity) < cap : stack.getCount() < stack.getMaxStackSize();
	}

	/**
	 * Replaces vanilla's merging for {@code self}. Returns false (vanilla merges) when MejtaSMP is not
	 * running.
	 */
	public static boolean merge(ItemEntity self) {
		ConfigHandle config = MejtaSMP.configOrNull();
		if (config == null || !(self.level() instanceof ServerLevel level)) {
			return false;
		}
		double radius = config.get(SmpSettings.DROPS_MERGE_RADIUS);
		boolean piles = config.get(SmpSettings.DROPS_STACK);
		int cap = config.get(SmpSettings.DROPS_MAX_PILE);
		if (!eligible(self, piles, cap)) {
			return true;
		}
		// Flat like vanilla's merge box (which does not reach up or down at all), but wider.
		for (ItemEntity other : level.getEntitiesOfClass(ItemEntity.class, self.getBoundingBox().inflate(radius, VERTICAL_REACH, radius),
				o -> o != self && eligible(o, piles, cap))) {
			if (self.isRemoved()) {
				break;
			}
			if (other.isRemoved() || !eligible(other, piles, cap) || !eligible(self, piles, cap)
					|| !Objects.equals(((ItemEntityAccessor) self).mejtasmp$target(), ((ItemEntityAccessor) other).mejtasmp$target())
					|| !ItemStack.isSameItemSameComponents(self.getItem(), other.getItem())) {
				continue;
			}
			// Like vanilla: the bigger one takes the smaller.
			boolean selfBigger = total(self) >= total(other);
			ItemEntity to = selfBigger ? self : other;
			ItemEntity from = selfBigger ? other : self;
			if (piles) {
				if (PileMath.fits(total(to), total(from), cap)) {
					absorb(to, from);
				}
			} else {
				topUp(to, from);
			}
		}
		return true;
	}

	/** {@code from}'s whole pile goes into {@code to}; {@code from} is removed empty. */
	static void absorb(ItemEntity to, ItemEntity from) {
		int sum = total(to) + total(from);
		ItemStack prototype = to.getItem().copyWithCount(1);
		PileMath.Split split = PileMath.split(sum, prototype.getMaxStackSize());
		keepTimers(to, from);
		((PileHolder) from).mejtasmp$setExtra(0);
		from.setItem(ItemStack.EMPTY);
		from.discard();
		to.setItem(prototype.copyWithCount(split.visible()));
		((PileHolder) to).mejtasmp$setExtra(split.extra());
	}

	/** Vanilla's merge over a wider distance: fills {@code to}'s stack from {@code from}'s. */
	static void topUp(ItemEntity to, ItemEntity from) {
		ItemStack target = to.getItem();
		ItemStack source = from.getItem();
		int moved = Math.min(target.getMaxStackSize() - target.getCount(), source.getCount());
		if (moved <= 0) {
			return;
		}
		keepTimers(to, from);
		to.setItem(target.copyWithCount(target.getCount() + moved));
		int left = source.getCount() - moved;
		from.setItem(left > 0 ? source.copyWithCount(left) : ItemStack.EMPTY);
		if (left <= 0) {
			from.discard(); // a leftover pile count goes on as a new entity (discarded)
		}
	}

	private static void keepTimers(ItemEntity to, ItemEntity from) {
		ItemEntityAccessor a = (ItemEntityAccessor) to;
		ItemEntityAccessor b = (ItemEntityAccessor) from;
		a.mejtasmp$setPickupDelay(Math.max(a.mejtasmp$pickupDelay(), b.mejtasmp$pickupDelay()));
		a.mejtasmp$setAge(Math.min(a.mejtasmp$age(), b.mejtasmp$age()));
	}

	/**
	 * A pile's entity is being discarded: something took its visible stack. The rest of the pile goes on
	 * as a new item entity at the same place.
	 */
	public static void discarded(ItemEntity old) {
		PileHolder pile = (PileHolder) old;
		int extra = pile.mejtasmp$extra();
		if (extra <= 0) {
			return;
		}
		pile.mejtasmp$setExtra(0);
		if (!(old.level() instanceof ServerLevel level)) {
			return;
		}
		ItemStack prototype = pile.mejtasmp$prototype();
		if (prototype.isEmpty()) {
			MejtaSMP.log().warn("A pile of {} items lost its item type and could not go on", extra);
			return;
		}
		PileMath.Split split = PileMath.split(extra, prototype.getMaxStackSize());
		ItemEntity next = new ItemEntity(level, old.getX(), old.getY(), old.getZ(), prototype.copyWithCount(split.visible()));
		next.setDeltaMovement(Vec3.ZERO);
		((PileHolder) next).mejtasmp$setExtra(split.extra());
		ItemEntityAccessor from = (ItemEntityAccessor) old;
		ItemEntityAccessor to = (ItemEntityAccessor) next;
		to.mejtasmp$setPickupDelay(from.mejtasmp$pickupDelay());
		to.mejtasmp$setAge(from.mejtasmp$age());
		next.setTarget(from.mejtasmp$target());
		if (!level.addFreshEntity(next)) {
			MejtaSMP.log().warn("Could not place the rest of a pile ({} x {})", extra, prototype.getItem());
		}
	}

	/** Every tick of an item entity: keeps the pile's label up to date (every half second). */
	public static void tick(ItemEntity entity) {
		ConfigHandle config = MejtaSMP.configOrNull();
		if (config == null || entity.tickCount % LABEL_EVERY != 0 || entity.isRemoved() || !(entity.level() instanceof ServerLevel)) {
			return;
		}
		PileHolder pile = (PileHolder) entity;
		ItemStack stack = entity.getItem();
		int total = total(entity);
		boolean wanted = switch (config.get(SmpSettings.DROPS_SHOW_COUNT)) {
			case ALWAYS -> total > 1;
			case STACKED -> total > stack.getMaxStackSize();
			case NEVER -> false;
		};
		if (wanted && !stack.isEmpty()) {
			if (!pile.mejtasmp$labelled() && entity.hasCustomName()) {
				return; // a name someone else gave it
			}
			if (pile.mejtasmp$labelled() && pile.mejtasmp$labelTotal() == total) {
				return; // unchanged
			}
			pile.mejtasmp$setLabelTotal(total);
			Component label = Component.empty().append(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" \u00d7" + PileMath.format(total)).withStyle(ChatFormatting.YELLOW));
			if (!label.equals(entity.getCustomName())) {
				entity.setCustomName(label);
			}
			entity.setCustomNameVisible(true);
			pile.mejtasmp$setLabelled(true);
		} else if (pile.mejtasmp$labelled()) {
			pile.mejtasmp$setLabelTotal(-1);
			entity.setCustomName(null);
			entity.setCustomNameVisible(false);
			pile.mejtasmp$setLabelled(false);
		}
	}
}
