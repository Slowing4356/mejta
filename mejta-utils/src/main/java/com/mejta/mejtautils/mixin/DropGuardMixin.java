package com.mejta.mejtautils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;

import com.mejta.mejtautils.freeze.FreezeHooks;
import com.mejta.mejtautils.staffmode.StaffModeHooks;

/**
 * Every way a player can drop an item: the drop key, throwing from an open screen or dragging out of
 * it, and creative-mode drops. Blocked while frozen (if configured) and always in staff mode. In staff
 * mode every real container is also view-only: items taken would be lost when staff mode ends, and
 * staff tools must never be stored. The client is re-synced, so its prediction is undone.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class DropGuardMixin {
	private static final String ENSURE_SAME_THREAD =
			"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

	@Shadow
	public ServerPlayer player;

	@Inject(method = "handlePlayerAction", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtautils$dropKey(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		ServerboundPlayerActionPacket.Action action = packet.getAction();
		if ((action == ServerboundPlayerActionPacket.Action.DROP_ITEM || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)
				&& FreezeHooks.dropBlocked(player)) {
			player.inventoryMenu.sendAllDataToRemote();
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerClick", cancellable = true,
			at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"))
	private void mejtautils$throwFromScreen(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		boolean drop = packet.containerInput() == ContainerInput.THROW || packet.slotNum() == -999;
		if ((drop && FreezeHooks.dropBlocked(player)) || StaffModeHooks.containerClickBlocked(player)) {
			player.containerMenu.sendAllDataToRemote();
			ci.cancel();
		}
	}

	@Inject(method = "handleSetCreativeModeSlot", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = ENSURE_SAME_THREAD))
	private void mejtautils$creativeDrop(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		if (packet.slotNum() < 0 && FreezeHooks.dropBlocked(player)) {
			player.inventoryMenu.sendAllDataToRemote();
			ci.cancel();
		}
	}
}
