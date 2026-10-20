package com.mejta.mejtasmp.afk;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.status.PlayerStatusService;
import com.mejta.mejtasmp.SmpSettings;

/**
 * AFK players cannot be hurt by other players ({@code afk_protection.enabled}) and optionally by mobs
 * ({@code afk_protection.from_mobs}). Who is AFK is MejtaCore's status, the same MejtaQoL shows. A player in
 * combat is never protected, so going AFK cannot end a fight. Falling, lava, hunger and the like still hurt.
 */
public final class AfkProtection {
	private AfkProtection() {
	}

	/** Fabric's ALLOW_DAMAGE: false cancels the damage. */
	public static boolean allowDamage(ConfigHandle config, LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer victim)) {
			return true;
		}
		PlayerStatusService status = MejtaCoreAPI.status();
		if (!status.isAfk(victim.getUUID()) || status.inCombat(victim.getUUID())) {
			return true;
		}
		Entity attacker = source.getEntity();
		if (attacker instanceof ServerPlayer player && player != victim) {
			if (!config.get(SmpSettings.AFK_PROTECTION)) {
				return true;
			}
			if (config.get(SmpSettings.AFK_PROTECTION_NOTICE)) {
				player.sendOverlayMessage(Component.literal(victim.getGameProfile().name() + " is AFK and cannot be hurt.")
						.withStyle(ChatFormatting.YELLOW));
			}
			return false;
		}
		return !(attacker != null && !(attacker instanceof Player) && config.get(SmpSettings.AFK_PROTECTION_MOBS));
	}
}
