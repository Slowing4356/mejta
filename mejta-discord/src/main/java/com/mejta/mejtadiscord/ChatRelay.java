package com.mejta.mejtadiscord;

import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.presence.PresenceListener;
import com.mejta.mejtadiscord.net.Bridge;
import com.mejta.mejtadiscord.text.ChatFormat;
import com.mejta.mejtadiscord.text.Embeds;
import com.mejta.mejtadiscord.text.Inbound;
import com.mejta.mejtadiscord.text.Sanitizer;
import com.mejta.mejtadiscord.text.UserRateLimiter;

/**
 * The chat relay. Minecraft chat goes to Discord through the chat webhook, posted under the player's
 * name; Discord messages from the chat channel are shown in Minecraft as system messages. Nothing
 * from Discord is ever run as a command: it is only displayed, as plain text.
 */
final class ChatRelay {
	private final UserRateLimiter limiter = new UserRateLimiter(System::currentTimeMillis);

	void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				toDiscord(sender.getGameProfile().name(), sender.getUUID(), message.signedContent()));
		// Only visible players are reported (vanished staff are not), plus staff's fake joins and leaves.
		MejtaCoreAPI.presence().addListener((uuid, name, kind, real) -> presence(uuid, name, kind));
	}

	private void toDiscord(String player, UUID id, String text) {
		MejtaDiscord.Snapshot s = MejtaDiscord.snapshot();
		Bridge bridge = MejtaDiscord.bridge();
		if (s == null || bridge == null || !s.enabled() || !s.chatToDiscord()) {
			return;
		}
		String content = Sanitizer.forDiscord(text, 2000);
		if (content.isBlank()) {
			return;
		}
		bridge.send(Bridge.CHAT, Embeds.text(username(s, player, id), avatar(s, player, id), content), false);
	}

	private void presence(UUID id, String player, PresenceListener.Kind kind) {
		MejtaDiscord.Snapshot s = MejtaDiscord.snapshot();
		Bridge bridge = MejtaDiscord.bridge();
		if (s == null || bridge == null || !s.enabled() || !s.chatToDiscord() || !s.joinLeave()) {
			return;
		}
		String text = "_" + (kind == PresenceListener.Kind.JOIN ? "joined the server" : "left the server") + "_";
		bridge.send(Bridge.CHAT, Embeds.text(username(s, player, id), avatar(s, player, id), text), false);
	}

	private static String username(MejtaDiscord.Snapshot s, String player, UUID id) {
		String name = ChatFormat.fill(s.chatUsername(), Map.of("player", player, "uuid", id.toString()));
		return name.isBlank() ? player : name;
	}

	private static String avatar(MejtaDiscord.Snapshot s, String player, UUID id) {
		return ChatFormat.fill(s.chatAvatar(), Map.of("player", player, "uuid", id.toString()));
	}

	/** Called on the gateway thread; the message is shown on the server thread. */
	void toMinecraft(Inbound in) {
		MejtaDiscord.Snapshot s = MejtaDiscord.snapshot();
		MinecraftServer server = MejtaDiscord.server();
		if (s == null || server == null || !s.enabled() || !s.chatToMinecraft()) {
			return;
		}
		if (!limiter.tryAcquire(in.userId(), s.ratePerMinute())) {
			return;
		}
		String user = Sanitizer.forMinecraft(in.name(), 32, s.filteredWords());
		String message = Sanitizer.forMinecraft(in.content(), s.maxLength(), s.filteredWords());
		if (message.isBlank()) {
			return;
		}
		Component line = Component.literal(ChatFormat.fill(s.minecraftFormat(), Map.of("user", user, "message", message)));
		server.execute(() -> server.getPlayerList().broadcastSystemMessage(line, false));
	}
}
