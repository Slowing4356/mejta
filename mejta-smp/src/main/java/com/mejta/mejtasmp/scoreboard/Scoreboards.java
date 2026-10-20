package com.mejta.mejtasmp.scoreboard;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtasmp.SmpPermissions;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.SmpSettings.SidebarView;
import com.mejta.mejtasmp.stats.PlayerStats;
import com.mejta.mejtasmp.stats.Stat;
import com.mejta.mejtasmp.stats.StatBook;
import com.mejta.mejtasmp.util.ScopedDataStore;
import com.mejta.mejtasmp.util.Templates;

/**
 * Sidebars and the player list score: takes everyone's numbers every 2 seconds and updates what each
 * player sees. Each player's choice of sidebar is kept in the data store ({@code sidebar/<uuid>}).
 * Server thread only.
 */
public final class Scoreboards {
	static final int UPDATE_TICKS = 40;
	private static final String VIEW_FOLDER = "sidebar";

	private final ConfigHandle config;
	private final StatBook book;
	private final ScopedDataStore store;
	private final Sidebar sidebar = new Sidebar();
	private final Map<UUID, SidebarView> views = new ConcurrentHashMap<>();
	/** Players who joined (not those the account limit refused on the way in). */
	private final java.util.Set<UUID> present = ConcurrentHashMap.newKeySet();

	public Scoreboards(ConfigHandle config, StatBook book, ScopedDataStore store) {
		this.config = config;
		this.book = book;
		this.store = store;
	}

	public StatBook book() {
		return book;
	}

	/** The sidebar the player picked, or the server's default. */
	public SidebarView view(UUID player) {
		return views.computeIfAbsent(player, id -> store.read(VIEW_FOLDER + "/" + id)
				.filter(json -> json.has("view"))
				.map(json -> {
					try {
						return SidebarView.valueOf(json.get("view").getAsString());
					} catch (RuntimeException e) {
						return null;
					}
				})
				.orElse(config.get(SmpSettings.SIDEBAR_DEFAULT)));
	}

	public void setView(ServerPlayer player, SidebarView view) {
		views.put(player.getUUID(), view);
		JsonObject json = new JsonObject();
		json.addProperty("view", view.name());
		store.write(VIEW_FOLDER + "/" + player.getUUID(), json);
		update(player.level().getServer());
	}

	public void onJoin(ServerPlayer player) {
		present.add(player.getUUID());
		book.refresh(player);
		update(player.level().getServer());
	}

	public void onLeave(ServerPlayer player) {
		if (!present.remove(player.getUUID())) {
			return;
		}
		book.leave(player);
		sidebar.forget(player.getUUID());
		views.remove(player.getUUID());
	}

	public void tick(MinecraftServer server) {
		if (server.getTickCount() % UPDATE_TICKS == 0) {
			update(server);
		}
	}

	/** Refreshes everyone's numbers, then every sidebar and the player list score. */
	public void update(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (ServerPlayer player : players) {
			book.refresh(player);
		}
		Component title = Component.literal(Templates.render(config.get(SmpSettings.SIDEBAR_TITLE), Map.of()));
		int online = MejtaCoreAPI.presence().publiclyOnline(server).size();
		Map<Stat, List<PlayerStats>> tops = new EnumMap<>(Stat.class);
		for (ServerPlayer player : players) {
			SidebarView view = view(player.getUUID());
			if (!config.get(SmpSettings.SIDEBAR_ENABLED) || view == SidebarView.OFF
					|| !MejtaCoreAPI.hasPermission(player, SmpPermissions.SIDEBAR)) {
				sidebar.hide(player);
				continue;
			}
			Stat ranked = SidebarLines.rankedBy(view);
			List<PlayerStats> top = ranked == null ? List.of() : tops.computeIfAbsent(ranked, s -> book.top(s, SidebarLines.TOP_SIZE));
			PlayerStats me = book.get(player.getUUID()).orElse(PlayerStats.empty(player.getUUID(), player.getGameProfile().name()));
			sidebar.show(player, title, SidebarLines.of(view, me, top, online));
		}
		TabList.update(server, config.get(SmpSettings.TAB_SCORE), book);
	}

	/** Settings changed or the server started: the player list score follows {@code scoreboard.tab_list}. */
	public void applyTabList(MinecraftServer server) {
		TabList.apply(server, config.get(SmpSettings.TAB_SCORE));
	}

	public void clear() {
		sidebar.clear();
		views.clear();
		present.clear();
	}
}
