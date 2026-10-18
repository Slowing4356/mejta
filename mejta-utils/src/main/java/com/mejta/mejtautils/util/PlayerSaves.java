package com.mejta.mejtautils.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * Writing a player's save file before MejtaUtils records that an item move is done (queued offline
 * changes, staff-mode restoration).
 *
 * <p>The game's single-player save method is not public; {@code PlayerList.saveAll()} saves every
 * player <em>in the player list</em>. A joining player is added to that list only late during login,
 * so this checks that the player is really in it: otherwise nothing would be saved, and a "done"
 * record written afterwards could lose items in a crash.
 */
public final class PlayerSaves {
	private PlayerSaves() {
	}

	/**
	 * Saves {@code player} (and everyone else online) to disk.
	 *
	 * @throws IllegalStateException if the player is not (or no longer) in the player list: they were not saved
	 */
	public static void save(ServerPlayer player) {
		PlayerList list = player.level().getServer().getPlayerList();
		if (list.getPlayer(player.getUUID()) != player) {
			throw new IllegalStateException(player.getGameProfile().name() + " is not in the player list, so their save cannot be written");
		}
		list.saveAll();
	}
}
