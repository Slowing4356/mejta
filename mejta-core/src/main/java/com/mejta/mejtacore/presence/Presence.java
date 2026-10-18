package com.mejta.mejtacore.presence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.presence.PresenceListener;
import com.mejta.mejtacore.api.presence.PresenceService;
import com.mejta.mejtacore.api.presence.VisibilityProvider;
import com.mejta.mejtacore.logging.Diagnostics;

/** {@link PresenceService} implementation; reports real joins and leaves of visible players. */
public final class Presence implements PresenceService {
	private static final Presence INSTANCE = new Presence();

	private final Map<String, VisibilityProvider> providers = new ConcurrentHashMap<>();
	private final List<PresenceListener> listeners = new CopyOnWriteArrayList<>();

	private Presence() {
	}

	public static Presence get() {
		return INSTANCE;
	}

	/** Common init. A player's hidden state is decided by the provider when the event fires. */
	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			if (!INSTANCE.isHidden(player.getUUID())) {
				INSTANCE.fire(player.getUUID(), player.getGameProfile().name(), PresenceListener.Kind.JOIN, true);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.player;
			if (!INSTANCE.isHidden(player.getUUID())) {
				INSTANCE.fire(player.getUUID(), player.getGameProfile().name(), PresenceListener.Kind.LEAVE, true);
			}
		});
	}

	@Override
	public void registerVisibilityProvider(String addonId, VisibilityProvider provider) {
		if (providers.putIfAbsent(addonId, provider) != null) {
			throw new IllegalStateException("Addon '" + addonId + "' already registered a visibility provider");
		}
	}

	@Override
	public boolean isHidden(UUID player) {
		for (Map.Entry<String, VisibilityProvider> entry : providers.entrySet()) {
			try {
				if (entry.getValue().isHidden(player)) {
					return true;
				}
			} catch (RuntimeException e) {
				Diagnostics.error(entry.getKey(), "Visibility provider failed", e);
			}
		}
		return false;
	}

	@Override
	public boolean canSee(ServerPlayer viewer, UUID target) {
		if (viewer.getUUID().equals(target)) {
			return true;
		}
		for (Map.Entry<String, VisibilityProvider> entry : providers.entrySet()) {
			try {
				VisibilityProvider provider = entry.getValue();
				if (provider.isHidden(target) && !provider.canSeeHidden(viewer, target)) {
					return false;
				}
			} catch (RuntimeException e) {
				Diagnostics.error(entry.getKey(), "Visibility provider failed", e);
			}
		}
		return true;
	}

	@Override
	public List<ServerPlayer> publiclyOnline(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isHidden(player.getUUID())) {
				out.add(player);
			}
		}
		return out;
	}

	@Override
	public void addListener(PresenceListener listener) {
		listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
	}

	@Override
	public void announce(UUID player, String name, PresenceListener.Kind kind) {
		fire(player, name, kind, false);
	}

	private void fire(UUID player, String name, PresenceListener.Kind kind, boolean real) {
		for (PresenceListener listener : listeners) {
			try {
				listener.onPresence(player, name, kind, real);
			} catch (RuntimeException e) {
				Diagnostics.error("presence", "Presence listener failed", e);
			}
		}
	}
}
