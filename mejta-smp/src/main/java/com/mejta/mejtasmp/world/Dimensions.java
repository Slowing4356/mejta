package com.mejta.mejtasmp.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpPermissions;
import com.mejta.mejtasmp.SmpSettings;

/** Closed dimensions ({@code dimensions.nether}, {@code dimensions.end}). Portals only; commands still work. */
public final class Dimensions {
	private static final long NOTICE_EVERY_MILLIS = 5_000;
	private static final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

	private Dimensions() {
	}

	/** Whether {@code dimension} is closed to portals. */
	public static boolean closed(ResourceKey<Level> dimension) {
		ConfigHandle config = MejtaSMP.configOrNull();
		if (config == null) {
			return false;
		}
		return dimension == Level.NETHER && !config.get(SmpSettings.NETHER_OPEN)
				|| dimension == Level.END && !config.get(SmpSettings.END_OPEN);
	}

	/**
	 * Whether a portal must not take {@code entity} to {@code dimension}. Players with
	 * {@code mejtasmp.dimensions.bypass} may go; others are told why (at most every 5 seconds).
	 */
	public static boolean refuse(Entity entity, ResourceKey<Level> dimension) {
		if (!closed(dimension)) {
			return false;
		}
		if (entity instanceof ServerPlayer player) {
			if (SmpPermissions.bypasses(player, SmpPermissions.DIMENSION_BYPASS)) {
				return false;
			}
			long now = System.currentTimeMillis();
			Long last = lastNotice.get(player.getUUID());
			if (last == null || now - last >= NOTICE_EVERY_MILLIS) {
				lastNotice.put(player.getUUID(), now);
				player.sendOverlayMessage(Component.literal(dimension == Level.NETHER ? "The Nether is closed." : "The End is closed.")
						.withStyle(ChatFormatting.RED));
			}
		}
		return true;
	}

	public static void forget(UUID player) {
		lastNotice.remove(player);
	}
}
