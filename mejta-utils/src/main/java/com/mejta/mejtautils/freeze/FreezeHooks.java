package com.mejta.mejtautils.freeze;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtautils.MejtaUtils;
import com.mejta.mejtautils.UtilsSettings;

/**
 * What frozen players (and players in staff mode, where it matters for item safety) may not do. The
 * Fabric events here cover interactions; the mixins call {@link #dropBlocked} and {@link #commandBlocked}
 * for item drops and commands, which Fabric has no events for. Safe when MejtaUtils is not initialized.
 */
public final class FreezeHooks {
	private FreezeHooks() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
				deny(player, UtilsSettings.FREEZE_BREAK, "break") ? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!deny(player, UtilsSettings.FREEZE_BREAK, "break"));
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				deny(player, UtilsSettings.FREEZE_PLACE, "use_block") ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				deny(player, UtilsSettings.FREEZE_INTERACT, "use_item") ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				deny(player, UtilsSettings.FREEZE_INTERACT, "use_entity") ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				deny(player, UtilsSettings.FREEZE_ATTACK, "attack") ? InteractionResult.FAIL : InteractionResult.PASS);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer p && isFrozen(p) && MejtaUtils.config().get(UtilsSettings.FREEZE_INVULNERABLE)));
	}

	public static boolean isFrozen(Player player) {
		FreezeManager m = MejtaUtils.freezeOrNull();
		return m != null && player instanceof ServerPlayer && m.isFrozen(player.getUUID());
	}

	private static boolean deny(Player player, Setting<Boolean> setting, String what) {
		if (!isFrozen(player) || !MejtaUtils.config().get(setting)) {
			return false;
		}
		MejtaUtils.freezeOrNull().blocked((ServerPlayer) player, what);
		return true;
	}

	/** Dropping or throwing items: frozen (if configured) or in staff mode (always: staff tools must not leave). */
	public static boolean dropBlocked(ServerPlayer player) {
		if (MejtaUtils.staffModeOrNull() != null && MejtaUtils.staffModeOrNull().isActive(player.getUUID())) {
			return true;
		}
		return deny(player, UtilsSettings.FREEZE_DROP, "drop");
	}

	/** Picking up items: frozen players and players in staff mode (picked-up items would be lost on exit). */
	public static boolean pickupBlocked(Player player) {
		if (!(player instanceof ServerPlayer sp)) {
			return false;
		}
		if (MejtaUtils.staffModeOrNull() != null && MejtaUtils.staffModeOrNull().isActive(sp.getUUID())) {
			return true;
		}
		return isFrozen(sp);
	}

	/** A frozen player's command, unless it is in freeze.allowed_commands. */
	public static boolean commandBlocked(ServerPlayer player, String command) {
		if (!isFrozen(player) || !MejtaUtils.config().get(UtilsSettings.FREEZE_COMMANDS)) {
			return false;
		}
		if (CommandFilter.parse(MejtaUtils.config().get(UtilsSettings.FREEZE_ALLOWED_COMMANDS)).isAllowed(command)) {
			return false;
		}
		MejtaUtils.freezeOrNull().blocked(player, "command");
		return true;
	}
}
