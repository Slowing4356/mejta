package com.mejta.mejtacore.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.NotificationProvider;
import com.mejta.mejtacore.api.notify.NotificationService;
import com.mejta.mejtacore.logging.Diagnostics;
import com.mejta.mejtacore.logging.MejtaLog;

/**
 * Bounded, single-threaded delivery queue. Publishing never blocks: when the queue is full the
 * notification is dropped and counted. Each provider is isolated; its failures are logged at most
 * once per minute.
 */
public final class NotificationBus implements NotificationService {
	private static final NotificationBus INSTANCE = new NotificationBus();
	private static final int QUEUE_CAPACITY = 1024;
	private static final long ERROR_LOG_INTERVAL_MS = 60_000;

	private final BlockingQueue<Notification> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private final List<NotificationProvider> providers = new CopyOnWriteArrayList<>();
	private final Map<String, Long> lastErrorLog = new ConcurrentHashMap<>();
	private final Map<UUID, Set<String>> muted = new ConcurrentHashMap<>();
	private final AtomicLong dropped = new AtomicLong();
	private volatile Thread worker;

	private NotificationBus() {
	}

	public static NotificationBus get() {
		return INSTANCE;
	}

	@Override
	public void publish(Notification notification) {
		Objects.requireNonNull(notification, "notification");
		startWorker();
		if (!queue.offer(notification)) {
			long count = dropped.incrementAndGet();
			if (count == 1 || count % 100 == 0) {
				Diagnostics.warn("notifications", "Queue full, dropped " + count + " notification(s) so far");
			}
		}
	}

	@Override
	public boolean registerProvider(NotificationProvider provider) {
		Objects.requireNonNull(provider, "provider");
		synchronized (providers) {
			if (providers.stream().anyMatch(p -> p.id().equals(provider.id()))) {
				Diagnostics.warn("notifications", "Duplicate notification provider '" + provider.id() + "' rejected");
				return false;
			}
			providers.add(provider);
		}
		MejtaLog.debug("Registered notification provider '{}'", provider.id());
		return true;
	}

	@Override
	public List<String> providerIds() {
		List<String> ids = new ArrayList<>();
		providers.forEach(p -> ids.add(p.id()));
		return ids;
	}

	@Override
	public void setMuted(UUID player, String channel, boolean mute) {
		if (mute) {
			muted.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(channel);
		} else {
			Set<String> channels = muted.get(player);
			if (channels != null) {
				channels.remove(channel);
			}
		}
	}

	@Override
	public boolean isMuted(UUID player, String channel) {
		Set<String> channels = muted.get(player);
		return channels != null && channels.contains(channel);
	}

	@Override
	public long droppedCount() {
		return dropped.get();
	}

	/** Number of notifications waiting for delivery (for tests and diagnostics). */
	public int pending() {
		return queue.size();
	}

	private void startWorker() {
		if (worker != null) {
			return;
		}
		synchronized (this) {
			if (worker == null) {
				Thread thread = new Thread(this::run, "MejtaCore-Notifications");
				thread.setDaemon(true);
				thread.start();
				worker = thread;
			}
		}
	}

	private void run() {
		while (true) {
			Notification notification;
			try {
				notification = queue.take();
			} catch (InterruptedException e) {
				return;
			}
			for (NotificationProvider provider : providers) {
				try {
					if (provider.accepts(notification)) {
						provider.deliver(notification);
					}
				} catch (Throwable t) {
					long now = System.currentTimeMillis();
					Long last = lastErrorLog.get(provider.id());
					if (last == null || now - last >= ERROR_LOG_INTERVAL_MS) {
						lastErrorLog.put(provider.id(), now);
						Diagnostics.error("notifications", "Provider '" + provider.id() + "' failed to deliver " + notification, t);
					}
				}
			}
		}
	}
}
