package com.mejta.mejtaqol.misc;

import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.location.Location;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.spawn.SpawnCommands;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Players;
import com.mejta.mejtaqol.util.Templates;

/** The join greeting, the first-join announcement and placement, and the death-position message. */
public final class Welcome {
	private Welcome() {
	}

	/** A player who has never left the server before is joining for the first time. */
	public static boolean isFirstJoin(ServerPlayer player) {
		return player.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
	}

	public static void onJoin(ServerPlayer player, boolean firstJoin) {
		ConfigHandle config = MejtaQoL.config();
		MinecraftServer server = player.level().getServer();
		if (firstJoin) {
			SpawnCommands.onFirstJoin(player);
		}
		if (!config.get(QolSettings.MOTD_ENABLED)) {
			return;
		}
		String name = Players.name(player);
		int online = MejtaCoreAPI.presence().publiclyOnline(server).size();
		player.sendSystemMessage(Component.literal(Templates.render(config.get(QolSettings.MOTD_TEXT),
				Map.of("player", name, "online", String.valueOf(online)))));
		if (firstJoin && !MejtaCoreAPI.presence().isHidden(player.getUUID())) {
			Component line = Component.literal(Templates.render(config.get(QolSettings.FIRST_JOIN_TEXT), Map.of("player", name)));
			for (ServerPlayer other : server.getPlayerList().getPlayers()) {
				if (other != player) {
					other.sendSystemMessage(line);
				}
			}
		}
	}

	/** On death: remembers the spot for {@code /back} and tells the player where it was. */
	public static void onDeath(ServerPlayer player) {
		ConfigHandle config = MejtaQoL.config();
		Location where = Locations.of(player);
		boolean back = config.get(QolSettings.BACK_ENABLED) && config.get(QolSettings.BACK_ON_DEATH);
		if (back) {
			MejtaQoL.teleports().setBack(player.getUUID(), where);
		}
		if (config.get(QolSettings.DEATH_COORDINATES)) {
			MutableComponent line = Msg.info("You died at ").append(Msg.highlight(where.describe())).append(Msg.info("."));
			if (back && MejtaCoreAPI.hasPermission(player, QolPermissions.BACK)) {
				line.append(Component.literal(" ")).append(Msg.button("Back", "/back", ChatFormatting.AQUA));
			}
			player.sendSystemMessage(line);
		}
	}
}
