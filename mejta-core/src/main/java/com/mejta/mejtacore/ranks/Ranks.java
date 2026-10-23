package com.mejta.mejtacore.ranks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.config.Setting;
import com.mejta.mejtacore.api.rank.Rank;
import com.mejta.mejtacore.api.rank.RankListener;
import com.mejta.mejtacore.api.rank.RankService;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.core.CoreSettings;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.permissions.PermissionRegistry;
import com.mejta.mejtacore.storage.RecordLogs;

/**
 * {@link RankService} implementation. Ranks are kept in MejtaCore's data store under {@code ranks/<uuid>} and cached
 * after their first read. Changes run on the server thread.
 */
public final class Ranks implements RankService {
	private static final Ranks INSTANCE = new Ranks();
	private static final String FOLDER = "ranks";
	/** Playtime is checked once a minute. */
	private static final int CHECK_TICKS = 20 * 60;

	private final Map<UUID, Optional<RankRecord>> cache = new ConcurrentHashMap<>();
	private final List<RankListener> listeners = new CopyOnWriteArrayList<>();
	private volatile ConfigHandle config;
	private volatile DataStore store;

	private Ranks() {
	}

	public static Ranks get() {
		return INSTANCE;
	}

	/** Common init: playtime checks on join and once a minute. */
	public static void register(ConfigHandle coreConfig) {
		INSTANCE.config = coreConfig;
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> INSTANCE.checkPlaytime(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % CHECK_TICKS == 0) {
				for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
					INSTANCE.checkPlaytime(player);
				}
			}
		});
		// A single-player world may be followed by another one with other data.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> INSTANCE.cache.clear());
	}

	// ---------------------------------------------------------------- API

	@Override
	public Rank rank(UUID player) {
		return player == null ? Rank.MEMBER : record(player).map(RankRecord::rank).orElse(Rank.MEMBER);
	}

	@Override
	public Component prefix(Rank rank) {
		Setting<String> setting = switch (rank) {
			case MEMBER -> null;
			case BRONZE -> CoreSettings.RANKS_PREFIX_BRONZE;
			case SILVER -> CoreSettings.RANKS_PREFIX_SILVER;
			case GOLD -> CoreSettings.RANKS_PREFIX_GOLD;
		};
		ConfigHandle c = config;
		if (setting == null || c == null) {
			return Component.empty();
		}
		return styled(c.get(setting));
	}

	@Override
	public int playtimeHours() {
		ConfigHandle c = config;
		return c == null ? 0 : c.get(CoreSettings.RANKS_PLAYTIME_HOURS);
	}

	@Override
	public void addRankListener(RankListener listener) {
		listeners.add(listener);
	}

	// ---------------------------------------------------------------- staff

	/** The stored rank, if any. */
	public Optional<RankRecord> record(UUID player) {
		return cache.computeIfAbsent(player, id -> {
			try {
				return store().read(DataStore.playerKey(FOLDER, id)).flatMap(RankRecord::fromJson);
			} catch (RuntimeException e) {
				Diagnostics.error(MejtaCore.MOD_ID, "Could not read the rank of " + id, e);
				return Optional.empty();
			}
		});
	}

	/** Every stored rank, by player. */
	public Map<UUID, RankRecord> all() {
		Map<UUID, RankRecord> out = new java.util.LinkedHashMap<>();
		for (String key : store().keys(FOLDER)) {
			String id = key.substring(key.lastIndexOf('/') + 1);
			try {
				UUID uuid = UUID.fromString(id);
				record(uuid).ifPresent(r -> out.put(uuid, r));
			} catch (IllegalArgumentException ignored) {
				// not a player file
			}
		}
		return out;
	}

	/** Stores the rank (also {@link Rank#MEMBER}, which stops playtime from giving Bronze later). */
	public void set(MinecraftServer server, AuditActor actor, UUID player, String name, Rank rank, String source) {
		Rank before = rank(player);
		RankRecord record = new RankRecord(rank, name, source, System.currentTimeMillis());
		store().write(DataStore.playerKey(FOLDER, player), record.toJson());
		cache.put(player, Optional.of(record));
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCore.MOD_ID, "rank.set").actor(actor).target(player, name)
				.detail("rank", rank.id()).detail("before", before.id()).detail("source", source).build(),
				PermissionRegistry.RANKS_MANAGE);
		MejtaLog.CORE.info("[ranks] {} set the rank of {} to {} ({})", actor.name(), name, rank.displayName(), source);
		changed(server, player, before, rank);
	}

	/** Removes the stored rank: the player is a Member who may earn Bronze by playtime again. */
	public void clear(MinecraftServer server, AuditActor actor, UUID player, String name) {
		Rank before = rank(player);
		store().delete(DataStore.playerKey(FOLDER, player));
		cache.put(player, Optional.empty());
		MejtaCoreAPI.audit().record(AuditEntry.builder(MejtaCore.MOD_ID, "rank.clear").actor(actor).target(player, name)
				.detail("before", before.id()).build(), PermissionRegistry.RANKS_MANAGE);
		changed(server, player, before, Rank.MEMBER);
	}

	/** Playtime in ticks, as counted by the game's statistics. */
	public static long playtimeTicks(ServerPlayer player) {
		return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
	}

	// ---------------------------------------------------------------- internals

	void checkPlaytime(ServerPlayer player) {
		int hours = playtimeHours();
		if (hours <= 0 || !RankRecord.mayEarn(record(player.getUUID())) || playtimeTicks(player) < RankRecord.earnTicks(hours)) {
			return;
		}
		set(player.level().getServer(), AuditActor.system(MejtaCore.MOD_ID), player.getUUID(), player.getGameProfile().name(),
				Rank.BRONZE, RankRecord.PLAYTIME);
		player.sendSystemMessage(Component.literal("You have played " + hours + " hours and earned the ")
				.withStyle(ChatFormatting.GREEN)
				.append(prefix(Rank.BRONZE))
				.append(Component.literal(" rank. Thanks for being part of the server!").withStyle(ChatFormatting.GREEN)));
	}

	private void changed(MinecraftServer server, UUID player, Rank before, Rank after) {
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(player);
		if (online != null) {
			server.getCommands().sendCommands(online); // perk commands appear or disappear at once
		}
		if (before == after) {
			return;
		}
		for (RankListener listener : listeners) {
			try {
				listener.onRankChange(player, before, after);
			} catch (RuntimeException e) {
				Diagnostics.error(MejtaCore.MOD_ID, "A rank listener failed", e);
			}
		}
	}

	private DataStore store() {
		DataStore s = store;
		if (s == null) {
			s = RecordLogs.dataStore(MejtaCore.MOD_ID);
			store = s;
		}
		return s;
	}

	/** {@code &6[Bronze]} as a component: {@code &} colour and format codes, {@code &&} for a plain {@code &}. */
	static MutableComponent styled(String text) {
		MutableComponent out = Component.empty();
		Style style = Style.EMPTY;
		StringBuilder run = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '&' && i + 1 < text.length()) {
				char next = Character.toLowerCase(text.charAt(i + 1));
				if (next == '&') {
					run.append('&');
					i++;
					continue;
				}
				ChatFormatting format = ChatFormatting.getByCode(next);
				if (format != null) {
					if (!run.isEmpty()) {
						out.append(Component.literal(run.toString()).withStyle(style));
						run.setLength(0);
					}
					style = format == ChatFormatting.RESET ? Style.EMPTY
							: format.isColor() ? Style.EMPTY.withColor(format) : style.applyFormat(format);
					i++;
					continue;
				}
			}
			run.append(c);
		}
		if (!run.isEmpty()) {
			out.append(Component.literal(run.toString()).withStyle(style));
		}
		return out;
	}
}
