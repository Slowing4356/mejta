package com.mejta.mejtacore.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.menu.MenuClick;
import com.mejta.mejtacore.api.menu.ServerMenu;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.network.MenuPayloads.ClickMenu;
import com.mejta.mejtacore.network.MenuPayloads.CloseMenu;
import com.mejta.mejtacore.network.MenuPayloads.MenuClosed;
import com.mejta.mejtacore.network.MenuPayloads.MenuPreference;
import com.mejta.mejtacore.network.MenuPayloads.MenuSlot;
import com.mejta.mejtacore.network.MenuPayloads.OpenMenu;

/**
 * Shows {@link ServerMenu}s as MejtaCore screens to players whose client has MejtaCore 1.7+ and asked for them;
 * everyone else gets the chest ({@link ButtonMenu}). The server stays in charge exactly as with the chest: the
 * client only sees copies of the icons and reports which one was clicked, the click handler runs here after
 * {@code validWhile} is checked, and {@code onClose} runs once however the menu ends (closed by the player, by a
 * button, by another menu or container opening, by death or by leaving).
 */
public final class ScreenMenus {
	private static final int MAX_CLICKS_PER_SECOND = 20;
	/** At most this many menus opened by close handlers in a row before one is shown (guards against loops). */
	private static final int MAX_CHAINED_CLOSES = 8;

	/** Open menus by player. Server thread only. */
	private static final Map<UUID, Session> OPEN = new HashMap<>();
	/** Players whose client asked for screens. */
	private static final Set<UUID> WANT_SCREENS = ConcurrentHashMap.newKeySet();
	private static int nextSession = 1;

	private ScreenMenus() {
	}

	/** Common init: payload types and server-side handlers. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OpenMenu.TYPE, OpenMenu.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CloseMenu.TYPE, CloseMenu.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ClickMenu.TYPE, ClickMenu.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MenuClosed.TYPE, MenuClosed.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MenuPreference.TYPE, MenuPreference.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(MenuPreference.TYPE, (payload, ctx) -> {
			if (payload.screens()) {
				WANT_SCREENS.add(ctx.player().getUUID());
			} else {
				WANT_SCREENS.remove(ctx.player().getUUID());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ClickMenu.TYPE, (payload, ctx) -> ctx.server().execute(() -> clicked(ctx.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(MenuClosed.TYPE, (payload, ctx) -> ctx.server().execute(() -> closedByPlayer(ctx.player(), payload.session())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUUID();
			WANT_SCREENS.remove(id);
			server.execute(() -> {
				Session session = OPEN.remove(id);
				if (session != null) {
					session.finish(false);
				}
			});
		});
		ServerTickEvents.END_SERVER_TICK.register(ScreenMenus::tick);
	}

	/** Whether {@code player}'s client shows server menus as screens. */
	public static boolean usesScreens(ServerPlayer player) {
		return WANT_SCREENS.contains(player.getUUID()) && player.connection != null && ServerPlayNetworking.canSend(player, OpenMenu.TYPE);
	}

	/** The title of the menu {@code player} has open as a screen, if any (for tests and diagnostics). */
	public static Optional<String> openTitle(ServerPlayer player) {
		Session session = OPEN.get(player.getUUID());
		return session == null ? Optional.empty() : Optional.of(session.definition.title().getString());
	}

	/** Opens {@code definition} for {@code player}: as a screen if their client asked for one, else as a chest. */
	public static boolean open(ServerPlayer player, ServerMenu definition) {
		if (!usesScreens(player)) {
			return ButtonMenu.open(player, definition);
		}
		if (!definition.validWhile().test(player)) {
			return false;
		}
		// Like opening a chest: whatever the player had open closes first and its close handler runs. A close
		// handler may open another menu (a cancelled confirmation reopens the list), which this one then replaces.
		for (int i = 0; i < MAX_CHAINED_CLOSES; i++) {
			Session previous = OPEN.remove(player.getUUID());
			if (previous == null) {
				break;
			}
			previous.finish(false);
		}
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
		Session session = new Session(nextSession++, player, definition);
		OPEN.put(player.getUUID(), session);
		session.send(false);
		return true;
	}

	private static void clicked(ServerPlayer player, ClickMenu payload) {
		Session session = OPEN.get(player.getUUID());
		if (session == null || session.id != payload.session() || session.closed || !session.allowClick()) {
			return;
		}
		if (!session.definition.validWhile().test(player)) {
			session.close();
			return;
		}
		ServerMenu.Button button = session.definition.buttons().get(payload.slot());
		MenuClick.Type type = clickType(payload.type());
		if (button == null || type == null) {
			return;
		}
		try {
			button.handler().onClick(new MenuClick(player, payload.slot(), type, session));
		} catch (RuntimeException e) {
			Diagnostics.error("menu", "Click handler of slot " + payload.slot() + " in menu '" + session.definition.title().getString()
					+ "' failed", e);
		}
	}

	private static MenuClick.Type clickType(String name) {
		try {
			return MenuClick.Type.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void closedByPlayer(ServerPlayer player, int sessionId) {
		Session session = OPEN.get(player.getUUID());
		if (session != null && session.id == sessionId) {
			OPEN.remove(player.getUUID());
			session.finish(false);
		}
	}

	private static void tick(MinecraftServer server) {
		if (OPEN.isEmpty()) {
			return;
		}
		for (Session session : new ArrayList<>(OPEN.values())) {
			if (session.closed) {
				continue;
			}
			ServerPlayer player = session.player;
			if (player.isRemoved() || !player.isAlive() || player.containerMenu != player.inventoryMenu) {
				// Gone, dead, or a real container opened over the menu (its screen replaced ours): the menu is over.
				OPEN.remove(player.getUUID(), session);
				session.finish(false);
				continue;
			}
			session.ticks++;
			int every = session.definition.refreshTicks();
			if (every > 0 && session.ticks % every == 0) {
				if (session.definition.validWhile().test(player)) {
					session.send(true);
				} else {
					session.close();
				}
			}
		}
	}

	/** One opening of a menu as a screen. */
	private static final class Session implements ServerMenu.Handle {
		final int id;
		final ServerPlayer player;
		final ServerMenu definition;
		int ticks;
		boolean closed;
		long clickWindow;
		int clicksInWindow;

		Session(int id, ServerPlayer player, ServerMenu definition) {
			this.id = id;
			this.player = player;
			this.definition = definition;
		}

		void send(boolean refresh) {
			if (player.connection == null) {
				return;
			}
			List<MenuSlot> slots = new ArrayList<>();
			int size = definition.rows() * 9;
			for (int i = 0; i < size; i++) {
				ServerMenu.Button button = definition.buttons().get(i);
				if (button == null) {
					continue;
				}
				ItemStack stack = ItemStack.EMPTY;
				try {
					stack = button.icon().get().copy();
				} catch (RuntimeException e) {
					Diagnostics.error("menu", "Icon of slot " + i + " in menu '" + definition.title().getString() + "' failed", e);
				}
				if (!stack.isEmpty()) {
					slots.add(new MenuSlot(i, stack, button.clickable()));
				}
			}
			ServerPlayNetworking.send(player, new OpenMenu(id, definition.title(), definition.rows(), refresh, slots));
		}

		boolean allowClick() {
			long now = System.currentTimeMillis();
			if (now - clickWindow >= 1000) {
				clickWindow = now;
				clicksInWindow = 0;
			}
			return ++clicksInWindow <= MAX_CLICKS_PER_SECOND;
		}

		@Override
		public void refresh() {
			if (!closed) {
				send(true);
			}
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			OPEN.remove(player.getUUID(), this);
			finish(true);
		}

		/** Ends the menu once: tells the client (if it still shows it) and runs the close handler. */
		void finish(boolean tellClient) {
			if (closed) {
				return;
			}
			closed = true;
			if (tellClient && player.connection != null && ServerPlayNetworking.canSend(player, CloseMenu.TYPE)) {
				ServerPlayNetworking.send(player, new CloseMenu(id));
			}
			try {
				definition.onClose().accept(player);
			} catch (RuntimeException e) {
				Diagnostics.error("menu", "Close handler of menu '" + definition.title().getString() + "' failed", e);
			}
		}
	}
}
