package com.mejta.mejtacrates;

import java.util.Optional;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.CrateStore;
import com.mejta.mejtacrates.menu.CrateMenus;

/**
 * Crate blocks and physical keys in the world: right-clicking a crate block opens it with a key (a physical key
 * in the hand first, else a virtual one) or shows its rewards; left-clicking shows its rewards; crate blocks
 * cannot be broken. Right-clicking a physical key opens its crate where {@code keys.physical_open_anywhere}
 * allows it, and keys are never placed (they are tripwire hooks by default).
 */
public final class CrateHooks {
	private CrateHooks() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				player instanceof ServerPlayer sp ? useBlock(sp, level, hand, hit.getBlockPos()) : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				player instanceof ServerPlayer sp && KeyItems.isKey(sp.getItemInHand(hand)) ? useKey(sp, hand) : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			Optional<CrateStore.CrateBlock> block = block(level, pos);
			if (block.isEmpty()) {
				return InteractionResult.PASS;
			}
			if (MejtaCoreAPI.hasPermission(sp, CratePermissions.USE)) {
				MejtaCrates.service().store().crate(block.get().crate()).ifPresent(crate -> CrateMenus.preview(sp, crate, 0, false));
			}
			return InteractionResult.FAIL;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> block(level, pos).isEmpty());
	}

	private static Optional<CrateStore.CrateBlock> block(Level level, BlockPos pos) {
		CrateService service = MejtaCrates.serviceOrNull();
		if (service == null) {
			return Optional.empty();
		}
		return service.store().block(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
	}

	private static InteractionResult useBlock(ServerPlayer player, Level level, InteractionHand hand, BlockPos pos) {
		Optional<CrateStore.CrateBlock> block = block(level, pos);
		if (block.isEmpty()) {
			// A key is never placed, also not on another block.
			return KeyItems.isKey(player.getItemInHand(hand)) ? useKey(player, hand) : InteractionResult.PASS;
		}
		if (hand != InteractionHand.MAIN_HAND || !MejtaCoreAPI.hasPermission(player, CratePermissions.USE)) {
			return InteractionResult.FAIL;
		}
		CrateService service = MejtaCrates.service();
		Optional<Crate> crate = service.store().crate(block.get().crate());
		if (crate.isEmpty()) {
			player.sendSystemMessage(Component.literal("This crate no longer exists.").withStyle(ChatFormatting.GRAY));
			return InteractionResult.FAIL;
		}
		Optional<KeyItems.KeyInfo> key = KeyItems.read(player.getMainHandItem());
		if (key.isPresent() && key.get().crate().equals(crate.get().id())) {
			CrateMenus.tell(player, crate.get(), service.openPhysical(player, InteractionHand.MAIN_HAND, true));
		} else if (service.store().player(player.getUUID(), player.getGameProfile().name()).keys(crate.get().id()) > 0) {
			CrateMenus.tell(player, crate.get(), service.openVirtual(player, crate.get(), true));
		} else {
			if (key.isPresent()) {
				player.sendSystemMessage(Component.literal("This key does not open this crate.").withStyle(ChatFormatting.RED));
			} else {
				player.sendSystemMessage(Component.literal("You need a ").withStyle(ChatFormatting.RED).append(CrateTexts.name(crate.get()))
						.append(Component.literal(" key to open this crate.").withStyle(ChatFormatting.RED)));
			}
			CrateMenus.preview(player, crate.get(), 0, false);
		}
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult useKey(ServerPlayer player, InteractionHand hand) {
		CrateService service = MejtaCrates.serviceOrNull();
		if (service == null || !MejtaCoreAPI.hasPermission(player, CratePermissions.USE)) {
			return InteractionResult.FAIL;
		}
		Optional<KeyItems.KeyInfo> key = KeyItems.read(player.getItemInHand(hand));
		Optional<Crate> crate = key.flatMap(k -> service.store().crate(k.crate()));
		if (crate.isEmpty()) {
			player.sendSystemMessage(Component.literal("The crate of this key no longer exists.").withStyle(ChatFormatting.GRAY));
			return InteractionResult.FAIL;
		}
		if (!MejtaCrates.config().get(CrateSettings.PHYSICAL_OPEN_ANYWHERE)) {
			player.sendSystemMessage(Component.literal("Use this key at a ").withStyle(ChatFormatting.GRAY).append(CrateTexts.name(crate.get()))
					.append(Component.literal(" crate.").withStyle(ChatFormatting.GRAY)));
			return InteractionResult.FAIL;
		}
		CrateMenus.tell(player, crate.get(), service.openPhysical(player, hand, true));
		player.inventoryMenu.sendAllDataToRemote();
		return InteractionResult.SUCCESS;
	}
}
