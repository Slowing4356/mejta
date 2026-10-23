package com.mejta.mejtacore.notify;

import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.permission.MejtaPermission;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.core.ServerHolder;
import com.mejta.mejtacore.permissions.PermissionRegistry;

/**
 * Built-in provider: shows notifications that have an in-game audience to online players with that
 * permission, unless they muted the channel. Fields are shown when hovering the message.
 */
public final class InGameProvider implements NotificationProvider {
	public static final String ID = "ingame";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean accepts(Notification notification) {
		return notification.inGameAudience().isPresent()
				&& MejtaCore.coreConfig().get(CoreSettings.IN_GAME_NOTIFICATIONS)
				&& ServerHolder.current().isPresent();
	}

	@Override
	public void deliver(Notification notification) {
		MinecraftServer server = ServerHolder.current().orElse(null);
		MejtaPermission audience = notification.inGameAudience().orElse(null);
		if (server == null || audience == null) {
			return;
		}
		Component message = format(notification);
		server.execute(() -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				// Permission first: it is a cheap level check, while a player's mutes are read from disk once.
				if (PermissionRegistry.check(player.permissions(), player.getUUID(), audience)
						&& !NotificationBus.get().isMuted(player.getUUID(), notification.channel())) {
					player.sendSystemMessage(message);
				}
			}
		});
	}

	static Component format(Notification n) {
		ChatFormatting color = switch (n.severity()) {
			case INFO -> ChatFormatting.AQUA;
			case WARNING -> ChatFormatting.GOLD;
			case CRITICAL -> ChatFormatting.RED;
		};
		MutableComponent hover = Component.literal(n.channel()).withStyle(ChatFormatting.GRAY);
		for (Map.Entry<String, String> field : n.fields().entrySet()) {
			hover.append(Component.literal("\n" + field.getKey() + ": ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(field.getValue()).withStyle(ChatFormatting.WHITE));
		}
		MutableComponent line = Component.literal("[Mejta] ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(n.title()).withStyle(color));
		if (!n.message().isEmpty()) {
			line.append(Component.literal(": " + n.message()).withStyle(ChatFormatting.WHITE));
		}
		return line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
	}
}
