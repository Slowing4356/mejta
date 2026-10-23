package com.mejta.mejtacore.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Network messages that show a {@link com.mejta.mejtacore.api.menu.ServerMenu} as a MejtaCore screen instead of a
 * chest. Only exchanged with clients that have MejtaCore 1.7+ and asked for screens ({@link MenuPreference});
 * every other player gets the chest menu.
 *
 * @since 1.7.0
 */
public final class MenuPayloads {
	/** Most icons a menu can have (a 6-row chest). */
	static final int MAX_SLOTS = 54;

	private MenuPayloads() {
	}

	/** One icon of the menu. {@code clickable} is {@code false} for icons added with {@code ServerMenu.Builder.icon}. */
	public record MenuSlot(int slot, ItemStack icon, boolean clickable) {
		static void write(RegistryFriendlyByteBuf buf, MenuSlot s) {
			buf.writeVarInt(s.slot);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.icon);
			buf.writeBoolean(s.clickable);
		}

		static MenuSlot read(RegistryFriendlyByteBuf buf) {
			return new MenuSlot(buf.readVarInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readBoolean());
		}
	}

	/**
	 * Server to client: show (or, with the id of the menu already shown and {@code refresh}, redraw) a menu.
	 *
	 * @param session id of this opening of the menu; clicks and closes name it
	 */
	public record OpenMenu(int session, Component title, int rows, boolean refresh, List<MenuSlot> slots) implements CustomPacketPayload {
		public static final Type<OpenMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "open_menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenu> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.session);
					ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, p.title);
					buf.writeVarInt(p.rows);
					buf.writeBoolean(p.refresh);
					buf.writeVarInt(p.slots.size());
					for (MenuSlot slot : p.slots) {
						MenuSlot.write(buf, slot);
					}
				},
				buf -> {
					int session = buf.readVarInt();
					Component title = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
					int rows = buf.readVarInt();
					boolean refresh = buf.readBoolean();
					int count = buf.readVarInt();
					if (count < 0 || count > MAX_SLOTS) {
						throw new IllegalArgumentException("Menu with " + count + " icons");
					}
					List<MenuSlot> slots = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						slots.add(MenuSlot.read(buf));
					}
					return new OpenMenu(session, title, rows, refresh, slots);
				});

		@Override
		public Type<OpenMenu> type() {
			return TYPE;
		}
	}

	/** Server to client: the menu was closed on the server (a button closed it, or it is no longer valid). */
	public record CloseMenu(int session) implements CustomPacketPayload {
		public static final Type<CloseMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "close_menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CloseMenu> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeVarInt(p.session),
				buf -> new CloseMenu(buf.readVarInt()));

		@Override
		public Type<CloseMenu> type() {
			return TYPE;
		}
	}

	/** Client to server: an icon was clicked. {@code type} is a {@code MenuClick.Type} name. */
	public record ClickMenu(int session, int slot, String type) implements CustomPacketPayload {
		public static final Type<ClickMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "click_menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClickMenu> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.session);
					buf.writeVarInt(p.slot);
					buf.writeUtf(p.type, 32);
				},
				buf -> new ClickMenu(buf.readVarInt(), buf.readVarInt(), buf.readUtf(32)));

		@Override
		public Type<ClickMenu> type() {
			return TYPE;
		}
	}

	/** Client to server: the player closed the menu's screen. */
	public record MenuClosed(int session) implements CustomPacketPayload {
		public static final Type<MenuClosed> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "menu_closed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MenuClosed> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeVarInt(p.session),
				buf -> new MenuClosed(buf.readVarInt()));

		@Override
		public Type<MenuClosed> type() {
			return TYPE;
		}
	}

	/** Client to server, on joining and when the setting changes: whether to show server menus as screens. */
	public record MenuPreference(boolean screens) implements CustomPacketPayload {
		public static final Type<MenuPreference> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "menu_preference"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MenuPreference> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBoolean(p.screens),
				buf -> new MenuPreference(buf.readBoolean()));

		@Override
		public Type<MenuPreference> type() {
			return TYPE;
		}
	}
}
