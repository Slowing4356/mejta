package com.mejta.mejtamarket.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/** MejtaMarket's chat styles: one colour per kind of message, and clickable buttons. */
public final class Msg {
	private Msg() {
	}

	public static MutableComponent info(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent success(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	public static MutableComponent error(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}

	public static MutableComponent highlight(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GOLD);
	}

	/** {@code [label]} that runs {@code command} (with its leading slash) when clicked. */
	public static MutableComponent button(String label, String command, ChatFormatting colour) {
		return Component.literal("[" + label + "]").withStyle(style -> style.withColor(colour).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	/** {@code [label]} that puts {@code command} into the chat box when clicked. */
	public static MutableComponent suggest(String label, String command, ChatFormatting colour) {
		return Component.literal("[" + label + "]").withStyle(style -> style.withColor(colour)
				.withClickEvent(new ClickEvent.SuggestCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	public static void send(ServerPlayer player, Component message) {
		player.sendSystemMessage(message);
	}

	/** Above the hotbar instead of in chat. */
	public static void actionBar(ServerPlayer player, Component message) {
		player.sendOverlayMessage(message);
	}
}
