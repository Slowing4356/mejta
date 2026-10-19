package com.mejta.mejtaqol.afk;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.status.PlayerStatusService;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Durations;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Players;
import com.mejta.mejtaqol.util.Templates;

/**
 * QoL's side of AFK: {@code /afk}, the optional kick, the broadcast and the {@code [AFK]} tag in the
 * player list. Who is AFK is MejtaCore's status ({@code MejtaCoreAPI.status()}): it notices idling and
 * activity for every addon. Server thread only.
 */
public final class AfkManager {
	private final ConfigHandle config;

	public AfkManager(ConfigHandle config) {
		this.config = config;
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaQoL.ID, "afk", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.AFK_ENABLED, QolPermissions.AFK))
				.executes(c -> MejtaQoL.afk().toggle(c.getSource().getPlayerOrException()) ? 1 : 0));
		MejtaCoreAPI.status().addAfkListener((player, afk, cause) -> {
			AfkManager manager = MejtaQoL.afkOrNull();
			if (manager != null) {
				manager.changed(player, afk);
			}
		});
	}

	public boolean isAfk(UUID player) {
		return config.get(QolSettings.AFK_ENABLED) && MejtaCoreAPI.status().isAfk(player);
	}

	/** The name shown in the player list while AFK, or {@code null} to leave it unchanged. */
	public Component tabListName(ServerPlayer player, Component current) {
		if (!config.get(QolSettings.AFK_TAB_LIST) || !isAfk(player.getUUID())) {
			return null;
		}
		MutableComponent base = (current != null ? current : player.getDisplayName()).copy();
		return base.append(Component.literal(" [AFK]").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * {@code /afk}: toggles. Going AFK is refused in combat, so it cannot be used to escape a fight.
	 * Returns whether the player is now AFK.
	 */
	public boolean toggle(ServerPlayer player) {
		PlayerStatusService status = MejtaCoreAPI.status();
		if (status.isAfk(player.getUUID())) {
			status.setAfk(player, false);
			return false;
		}
		long combat = status.combatRemainingMillis(player.getUUID());
		if (combat > 0) {
			player.sendSystemMessage(Msg.error("You are in combat. You can go AFK in " + Durations.format(combat) + "."));
			return false;
		}
		status.setAfk(player, true);
		return true;
	}

	/** Once a second: kicks players idle longer than {@code afk.kick_minutes}. */
	public void tick(MinecraftServer server) {
		int kick = config.get(QolSettings.AFK_KICK_MINUTES);
		if (!config.get(QolSettings.AFK_ENABLED) || kick <= 0) {
			return;
		}
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (MejtaCoreAPI.status().idleMillis(player.getUUID()) >= kick * 60_000L
					&& !MejtaCoreAPI.hasPermission(player, QolPermissions.AFK_KICK_EXEMPT)) {
				player.connection.disconnect(Component.literal(Templates.render(config.get(QolSettings.AFK_KICK_MESSAGE), Map.of())));
			}
		}
	}

	/** MejtaCore reported an AFK change: tell players and update the list name. */
	void changed(ServerPlayer player, boolean afk) {
		if (!config.get(QolSettings.AFK_ENABLED)) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		boolean hidden = MejtaCoreAPI.presence().isHidden(player.getUUID());
		if (config.get(QolSettings.AFK_BROADCAST) && !hidden) {
			Component line = Component.literal(Players.name(player) + (afk ? " is now AFK." : " is no longer AFK."))
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
			for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
				viewer.sendSystemMessage(line);
			}
		} else {
			player.sendSystemMessage(Component.literal(afk ? "You are now AFK." : "You are no longer AFK.")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		refreshTabList(player);
	}

	/** Re-sends the player's list name to everyone who may see them. */
	public void refreshTabList(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), List.of(player));
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (MejtaCoreAPI.presence().canSee(viewer, player.getUUID())) {
				viewer.connection.send(packet);
			}
		}
	}

	public int afkCount(MinecraftServer server) {
		return (int) server.getPlayerList().getPlayers().stream().filter(p -> MejtaCoreAPI.status().isAfk(p.getUUID())).count();
	}
}
