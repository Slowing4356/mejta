package com.mejta.mejtasmp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.mejta.mejtasmp.drops.PileHolder;
import com.mejta.mejtasmp.drops.Piles;

/**
 * Piles of dropped items ({@link Piles}): the extra count and its saving, MejtaSMP's merging in place of
 * vanilla's, and the two ways an item entity is destroyed with its items (damage such as lava, and
 * despawning), which take the whole pile with them.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin implements PileHolder {
	@Unique
	private static final String EXTRA_KEY = "mejtasmp_pile";
	@Unique
	private static final String PROTOTYPE_KEY = "mejtasmp_pile_item";
	@Unique
	private static final String LABEL_KEY = "mejtasmp_pile_label";

	@Unique
	private int mejtasmp$extra;
	@Unique
	private ItemStack mejtasmp$prototype = ItemStack.EMPTY;
	@Unique
	private boolean mejtasmp$labelled;
	@Unique
	private int mejtasmp$labelTotal = -1;

	@Override
	public int mejtasmp$extra() {
		return mejtasmp$extra;
	}

	@Override
	public void mejtasmp$setExtra(int extra) {
		mejtasmp$extra = Math.max(0, extra);
	}

	@Override
	public ItemStack mejtasmp$prototype() {
		return mejtasmp$prototype;
	}

	@Override
	public boolean mejtasmp$labelled() {
		return mejtasmp$labelled;
	}

	@Override
	public void mejtasmp$setLabelled(boolean labelled) {
		mejtasmp$labelled = labelled;
	}

	@Override
	public int mejtasmp$labelTotal() {
		return mejtasmp$labelTotal;
	}

	@Override
	public void mejtasmp$setLabelTotal(int total) {
		mejtasmp$labelTotal = total;
	}

	/** Remembers the item type: an emptied stack no longer knows it, but the rest of the pile needs it. */
	@Inject(method = "setItem", at = @At("HEAD"))
	private void mejtasmp$rememberItem(ItemStack stack, CallbackInfo ci) {
		if (!stack.isEmpty()) {
			mejtasmp$prototype = stack.copyWithCount(1);
		}
	}

	@Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$merge(CallbackInfo ci) {
		if (Piles.merge((ItemEntity) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
	private void mejtasmp$mergable(CallbackInfoReturnable<Boolean> cir) {
		Boolean mergable = Piles.mergable((ItemEntity) (Object) this);
		if (mergable != null) {
			cir.setReturnValue(mergable);
		}
	}

	/** Despawning (the second {@code discard()} in {@code tick}) takes the whole pile. */
	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V", ordinal = 1))
	private void mejtasmp$despawn(CallbackInfo ci) {
		mejtasmp$extra = 0;
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void mejtasmp$label(CallbackInfo ci) {
		Piles.tick((ItemEntity) (Object) this);
	}

	/** Destroyed by damage (lava, fire, cactus, explosions): the whole pile is destroyed, like a stack. */
	@Inject(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;discard()V"))
	private void mejtasmp$destroyed(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		mejtasmp$extra = 0;
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void mejtasmp$save(ValueOutput output, CallbackInfo ci) {
		if (mejtasmp$labelled) {
			output.putBoolean(LABEL_KEY, true);
		}
		if (mejtasmp$extra > 0) {
			output.putInt(EXTRA_KEY, mejtasmp$extra);
			if (!mejtasmp$prototype.isEmpty()) {
				output.store(PROTOTYPE_KEY, ItemStack.CODEC, mejtasmp$prototype);
			}
		}
	}

	/** Read first: if the visible stack fails to load, vanilla removes the entity, and the rest must go on. */
	@Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
	private void mejtasmp$load(ValueInput input, CallbackInfo ci) {
		mejtasmp$extra = Math.max(0, input.getIntOr(EXTRA_KEY, 0));
		mejtasmp$prototype = input.read(PROTOTYPE_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY);
		mejtasmp$labelled = input.getBooleanOr(LABEL_KEY, false);
	}
}
