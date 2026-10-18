package com.mejta.mejtacore.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mejta.mejtacore.api.metrics.Metrics;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.core.MejtaCore;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;
import com.mejta.mejtacore.metrics.MetricRegistry;
import com.mejta.mejtacore.storage.RecordLogs;

/**
 * Bounded delivery queues, one per provider, each with its own thread. A slow provider (a Discord
 * webhook waiting out a rate limit) therefore never delays the others. Publishing never blocks:
 * when a provider's queue is full the notification is dropped for that provider only and counted.
 * Each provider is isolated; its failures are logged at most once per minute.
 *
 * <p>In-game mutes are saved in MejtaCore's data store ({@code mejta-data/mejtacore/store/mutes/}) and
 * survive restarts.
 */
public final class NotificationBus implements NotificationService {
	private static final NotificationBus INSTANCE = new NotificationBus();
	private static final int QUEUE_CAPACITY = 1024;
	private static final long ERROR_LOG_INTERVAL_MS = 60_000;
	private static final String MUTES = "mutes";

	private final List<Lane> lanes = new CopyOnWriteArrayList<>();
	private final Map<UUID, Set<String>> muted = new ConcurrentHashMap<>();
	private volatile DataStore muteStore;

	/** One provider with its queue and worker thread. */
	private static final class Lane {
		final NotificationProvider provider;
		final BlockingQueue<Notification> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
		final AtomicLong dropped = new AtomicLong();
		volatile Metrics.Timer deliverTime;
		long lastErrorLog;

		Lane(NotificationProvider provider) {
			this.provider = provider;
		}
	}

	private NotificationBus() {
	}

	public static NotificationBus get() {
		return INSTANCE;
	}

	@Override
	public void publish(Notification notification) {
		Objects.requireNonNull(notification, "notification");
		for (Lane lane : lanes) {
			if (!lane.queue.offer(notification)) {
				long count = lane.dropped.incrementAndGet();
				if (count == 1 || count % 100 == 0) {
					Diagnostics.warn("notifications", "Queue of provider '" + lane.provider.id() + "' full, dropped " + count
							+ " notification(s) for it so far");
				}
			}
		}
	}

	@Override
	public boolean registerProvider(NotificationProvider provider) {
		Objects.requireNonNull(provider, "provider");
		Lane lane;
		synchronized (lanes) {
			if (lanes.stream().anyMatch(l -> l.provider.id().equals(provider.id()))) {
				Diagnostics.warn("notifications", "Duplicate notification provider '" + provider.id() + "' rejected");
				return false;
			}
			lane = new Lane(provider);
			lanes.add(lane);
		}
		registerMetrics(lane);
		Thread thread = new Thread(() -> run(lane), "MejtaCore-Notify-" + provider.id());
		thread.setDaemon(true);
		thread.start();
		MejtaLog.debug("Registered notification provider '{}'", provider.id());
		return true;
	}

	/** Queue depth, drops and delivery time of each provider, under {@code mejtacore:notify.*.<provider>}. */
	private static void registerMetrics(Lane lane) {
		String id = lane.provider.id().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
		id = id.substring(0, Math.min(id.length(), 40));
		try {
			MetricRegistry metrics = MetricRegistry.get();
			metrics.gauge(MejtaCore.MOD_ID, "notify.queue." + id, lane.queue::size);
			metrics.gauge(MejtaCore.MOD_ID, "notify.dropped." + id, lane.dropped::get);
			lane.deliverTime = metrics.timer(MejtaCore.MOD_ID, "notify.deliver." + id);
		} catch (IllegalArgumentException e) {
			Diagnostics.warn("notifications", "No metrics for provider '" + lane.provider.id() + "': " + e.getMessage());
		}
	}

	@Override
	public List<String> providerIds() {
		List<String> ids = new ArrayList<>();
		lanes.forEach(l -> ids.add(l.provider.id()));
		return ids;
	}

	@Override
	public void setMuted(UUID player, String channel, boolean mute) {
		Set<String> channels = mutes(player);
		boolean changed = mute ? channels.add(channel) : channels.remove(channel);
		if (changed) {
			saveMutes(player, channels);
		}
	}

	@Override
	public boolean isMuted(UUID player, String channel) {
		return mutes(player).contains(channel);
	}

	@Override
	public long droppedCount() {
		long total = 0;
		for (Lane lane : lanes) {
			total += lane.dropped.get();
		}
		return total;
	}

	/** Notifications waiting for delivery, summed over providers (for tests and diagnostics). */
	public int pending() {
		int total = 0;
		for (Lane lane : lanes) {
			total += lane.queue.size();
		}
		return total;
	}

	private void run(Lane lane) {
		while (true) {
			Notification notification;
			try {
				notification = lane.queue.take();
			} catch (InterruptedException e) {
				return;
			}
			try {
				if (lane.provider.accepts(notification)) {
					long start = Metrics.Timer.start();
					lane.provider.deliver(notification);
					Metrics.Timer timer = lane.deliverTime;
					if (timer != null) {
						timer.stop(start);
					}
				}
			} catch (Throwable t) {
				long now = System.currentTimeMillis();
				if (now - lane.lastErrorLog >= ERROR_LOG_INTERVAL_MS) {
					lane.lastErrorLog = now;
					Diagnostics.error("notifications", "Provider '" + lane.provider.id() + "' failed to deliver " + notification, t);
				}
			}
		}
	}

	// ---------------------------------------------------------------- persistent mutes

	private Set<String> mutes(UUID player) {
		return muted.computeIfAbsent(player, this::loadMutes);
	}

	private Set<String> loadMutes(UUID player) {
		Set<String> channels = ConcurrentHashMap.newKeySet();
		try {
			store().read(DataStore.playerKey(MUTES, player)).ifPresent(doc -> {
				JsonElement list = doc.get("channels");
				if (list != null && list.isJsonArray()) {
					for (JsonElement e : list.getAsJsonArray()) {
						if (e.isJsonPrimitive() && Notification.CHANNEL_PATTERN.matcher(e.getAsString()).matches()) {
							channels.add(e.getAsString());
						}
					}
				}
			});
		} catch (RuntimeException e) {
			Diagnostics.error("notifications", "Could not load notification mutes of " + player, e);
		}
		return channels;
	}

	private void saveMutes(UUID player, Set<String> channels) {
		try {
			String key = DataStore.playerKey(MUTES, player);
			if (channels.isEmpty()) {
				store().delete(key);
				return;
			}
			JsonArray list = new JsonArray();
			new TreeSet<>(channels).forEach(list::add);
			JsonObject doc = new JsonObject();
			doc.add("channels", list);
			store().write(key, doc);
		} catch (RuntimeException e) {
			Diagnostics.error("notifications", "Could not save notification mutes of " + player, e);
		}
	}

	private DataStore store() {
		DataStore store = muteStore;
		if (store == null) {
			store = RecordLogs.dataStore(MejtaCore.MOD_ID);
			muteStore = store;
		}
		return store;
	}
}
