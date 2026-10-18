package com.mejta.mejtacore.network;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Network messages for editing server configs from a client's MejtaCore menu. Only sent between a
 * server and clients that both have MejtaCore 1.2+; vanilla clients never receive them.
 */
public final class ConfigPayloads {
	/** Bumped when the message format changes incompatibly. */
	public static final int PROTOCOL = 1;
	/** Longest text a payload field carries; longer settings are not offered for remote editing. */
	static final int MAX_TEXT = 4096;

	private ConfigPayloads() {
	}

	/** One setting as the client needs to show and edit it. Numbers and values are text; "" = none. */
	public record RemoteSetting(String key, String type, String displayName, String description, String defaultValue,
			String value, String min, String max, List<String> options) {
		static void write(FriendlyByteBuf buf, RemoteSetting s) {
			buf.writeUtf(s.key);
			buf.writeUtf(s.type);
			buf.writeUtf(s.displayName);
			buf.writeUtf(s.description, MAX_TEXT);
			buf.writeUtf(s.defaultValue, MAX_TEXT);
			buf.writeUtf(s.value, MAX_TEXT);
			buf.writeUtf(s.min);
			buf.writeUtf(s.max);
			buf.writeCollection(s.options, FriendlyByteBuf::writeUtf);
		}

		static RemoteSetting read(FriendlyByteBuf buf) {
			return new RemoteSetting(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT),
					buf.readUtf(MAX_TEXT), buf.readUtf(), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf));
		}
	}

	public record RemoteConfig(String id, String name, List<RemoteSetting> settings) {
		static void write(FriendlyByteBuf buf, RemoteConfig c) {
			buf.writeUtf(c.id);
			buf.writeUtf(c.name);
			buf.writeCollection(c.settings, RemoteSetting::write);
		}

		static RemoteConfig read(FriendlyByteBuf buf) {
			return new RemoteConfig(buf.readUtf(), buf.readUtf(), buf.readList(RemoteSetting::read));
		}
	}

	/**
	 * Server to client: the configs this player may edit, with current values.
	 *
	 * @param permitted {@code false} if the player lacks {@code mejtacore.config.edit} ({@code configs} is empty)
	 */
	public record ServerConfigs(int protocol, boolean permitted, List<RemoteConfig> configs) implements CustomPacketPayload {
		public static final Type<ServerConfigs> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "server_configs"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigs> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.protocol);
					buf.writeBoolean(p.permitted);
					buf.writeCollection(p.configs, RemoteConfig::write);
				},
				buf -> new ServerConfigs(buf.readVarInt(), buf.readBoolean(), buf.readList(RemoteConfig::read)));

		@Override
		public Type<ServerConfigs> type() {
			return TYPE;
		}
	}

	/** Client to server: please send me the current {@link ServerConfigs}. */
	public record RequestConfigs() implements CustomPacketPayload {
		public static final Type<RequestConfigs> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "request_configs"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestConfigs> CODEC = StreamCodec.of((buf, p) -> {
		}, buf -> new RequestConfigs());

		@Override
		public Type<RequestConfigs> type() {
			return TYPE;
		}
	}

	/** Client to server: change one setting. {@code op} is a {@code ConfigEditing.Op} name. */
	public record EditConfig(String configId, String op, String key, String value) implements CustomPacketPayload {
		public static final Type<EditConfig> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "edit_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EditConfig> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.configId);
					buf.writeUtf(p.op);
					buf.writeUtf(p.key);
					buf.writeUtf(p.value, MAX_TEXT);
				},
				buf -> new EditConfig(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(MAX_TEXT)));

		@Override
		public Type<EditConfig> type() {
			return TYPE;
		}
	}

	/** Server to client: outcome of an {@link EditConfig}. */
	public record EditResult(boolean ok, String message) implements CustomPacketPayload {
		public static final Type<EditResult> TYPE = new Type<>(Identifier.fromNamespaceAndPath("mejtacore", "edit_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EditResult> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBoolean(p.ok);
					buf.writeUtf(p.message, MAX_TEXT);
				},
				buf -> new EditResult(buf.readBoolean(), buf.readUtf(MAX_TEXT)));

		@Override
		public Type<EditResult> type() {
			return TYPE;
		}
	}
}
