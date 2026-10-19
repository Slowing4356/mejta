package com.mejta.mejtaqol.afk;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtaqol.MejtaQoL;
import com.mejta.mejtaqol.QolPermissions;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.util.Cmds;
import com.mejta.mejtaqol.util.Players;
import com.mejta.mejtaqol.util.Templates;

/**
 * AFK status: {@code /afk}, automatic AFK after idling, the optional kick, the broadcast and the
 * {@code [AFK]} tag in the player list. A player counts as active when they look around, walk (being
 * carried by a vehicle or water does not count), chat or interact. Server thread only.
 */
public final class AfkManager {
	private record Pose(double x, double y, double z, float yaw, float pitch) {
	}

	private final ConfigHandle config;
	private final AfkClock clock = new AfkClock();
	private final Map<UUID, Pose> poses = new ConcurrentHashMap<>();

	public AfkManager(ConfigHandle config) {
		this.config = config;
	}

	public static void register() {
		MejtaCoreAPI.registerRootCommand(MejtaQoL.ID, "afk", (root, ctx) -> root
				.requires(Cmds.available(QolSettings.AFK_ENABLED, QolPermissions.AFK))
				.executes(c -> MejtaQoL.afk().toggle(c.getSource().getPlayerOrException()) ? 1 : 0));
	}

	public boolean isAfk(UUID player) {
		return config.get(QolSettings.AFK_ENABLED) && clock.isAfk(player);
	}

	/** The name shown in the player list while AFK, or {@code null} to leave it unchanged. */
	public Component tabListName(ServerPlayer player, Component current) {
		if (!config.get(QolSettings.AFK_TAB_LIST) || !isAfk(player.getUUID())) {
			return null;
		}
		MutableComponent base = (current != null ? current : player.getDisplayName()).copy();
		return base.append(Component.literal(" [AFK]").withStyle(ChatFormatting.GRAY));
	}

	/** {@code /afk}: returns whether the player is now AFK. */
	public boolean toggle(ServerPlayer player) {
		AfkClock.Change change = clock.toggle(player.getUUID(), System.currentTimeMillis());
		poses.put(player.getUUID(), pose(player));
		changed(player, change);
		return change == AfkClock.Change.BECAME_AFK;
	}

	/** The player chatted or interacted. */
	public void activity(ServerPlayer player) {
		AfkClock.Change change = clock.active(player.getUUID(), System.currentTimeMillis());
		if (change != AfkClock.Change.NONE) {
			changed(player, change);
		}
	}

	public void onJoin(ServerPlayer player) {
		clock.join(player.getUUID(), System.currentTimeMillis());
		poses.put(player.getUUID(), pose(player));
	}

	public void onLeave(UUID player) {
		clock.leave(player);
		poses.remove(player);
	}

	/** Once a second: compares each player's pose with the last one, then applies the idle rules. */
	public void tick(MinecraftServer server) {
		if (!config.get(QolSettings.AFK_ENABLED)) {
			return;
		}
		long now = System.currentTimeMillis();
		int auto = config.get(QolSettings.AFK_AUTO_MINUTES);
		int kick = config.get(QolSettings.AFK_KICK_MINUTES);
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			Pose before = poses.get(player.getUUID());
			Pose current = pose(player);
			poses.put(player.getUUID(), current);
			if (before != null && moved(player, before, current)) {
				activity(player);
				continue;
			}
			AfkClock.Change change = clock.check(player.getUUID(), now, auto, kick,
					MejtaCoreAPI.hasPermission(player, QolPermissions.AFK_KICK_EXEMPT));
			if (change == AfkClock.Change.KICK) {
				player.connection.disconnect(Component.literal(Templates.render(config.get(QolSettings.AFK_KICK_MESSAGE), Map.of())));
			} else if (change != AfkClock.Change.NONE) {
				changed(player, change);
			}
		}
	}

	private static Pose pose(ServerPlayer p) {
		return new Pose(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
	}

	private static boolean moved(ServerPlayer player, Pose a, Pose b) {
		if (Math.abs(a.yaw() - b.yaw()) > 1 || Math.abs(a.pitch() - b.pitch()) > 1) {
			return true;
		}
		if (player.isPassenger() || player.isInWater()) {
			return false;
		}
		double dx = a.x() - b.x();
		double dz = a.z() - b.z();
		return dx * dx + dz * dz > 0.01;
	}

	private void changed(ServerPlayer player, AfkClock.Change change) {
		boolean afk = change == AfkClock.Change.BECAME_AFK;
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

	public int afkCount() {
		return (int) poses.keySet().stream().filter(clock::isAfk).count();
	}

	public void reset() {
		clock.clear();
		poses.clear();
	}
}
