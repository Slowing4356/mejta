package com.mejta.mejtaqol.tpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Open teleport requests. A sender has at most one open request per target; a new one replaces it.
 * Requests expire after a timeout. Times are passed in (milliseconds). Server thread only.
 */
public final class TpaRequests {
	public enum Direction {
		/** {@code /tpa}: the sender goes to the target. */
		TO_TARGET,
		/** {@code /tpahere}: the target comes to the sender. */
		TO_SENDER
	}

	public record Request(UUID sender, String senderName, UUID target, String targetName, Direction direction, long createdMillis,
			long expiresMillis) {
		/** The player who will be teleported. */
		public UUID traveller() {
			return direction == Direction.TO_TARGET ? sender : target;
		}

		/** The player at the destination. */
		public UUID destination() {
			return direction == Direction.TO_TARGET ? target : sender;
		}
	}

	private final List<Request> open = new ArrayList<>();

	/** Adds a request, replacing an open one from the same sender to the same target. Returns the replaced one. */
	public Optional<Request> add(UUID sender, String senderName, UUID target, String targetName, Direction direction, long nowMillis,
			long timeoutMillis) {
		expire(nowMillis);
		Optional<Request> replaced = find(sender, target);
		replaced.ifPresent(open::remove);
		open.add(new Request(sender, senderName, target, targetName, direction, nowMillis, nowMillis + timeoutMillis));
		return replaced;
	}

	/** Removes and returns expired requests. */
	public List<Request> expire(long nowMillis) {
		List<Request> expired = new ArrayList<>();
		open.removeIf(r -> {
			if (r.expiresMillis() <= nowMillis) {
				expired.add(r);
				return true;
			}
			return false;
		});
		return expired;
	}

	/** Open requests sent to {@code target}, newest first. */
	public List<Request> incoming(UUID target) {
		return open.stream().filter(r -> r.target().equals(target))
				.sorted(Comparator.comparingLong(Request::createdMillis).reversed()).toList();
	}

	/** Open requests sent by {@code sender}, newest first. */
	public List<Request> outgoing(UUID sender) {
		return open.stream().filter(r -> r.sender().equals(sender))
				.sorted(Comparator.comparingLong(Request::createdMillis).reversed()).toList();
	}

	public Optional<Request> find(UUID sender, UUID target) {
		return open.stream().filter(r -> r.sender().equals(sender) && r.target().equals(target)).findFirst();
	}

	/**
	 * The request {@code target} answers: the one from {@code sender} if given, else the newest one.
	 * Removes it.
	 */
	public Optional<Request> take(UUID target, Optional<UUID> sender) {
		List<Request> in = incoming(target);
		Optional<Request> chosen = sender.isPresent()
				? in.stream().filter(r -> r.sender().equals(sender.get())).findFirst()
				: in.stream().findFirst();
		chosen.ifPresent(open::remove);
		return chosen;
	}

	public boolean remove(Request request) {
		return open.remove(request);
	}

	/** Removes every request sent by or to {@code player}, e.g. when they leave. */
	public List<Request> removeAll(UUID player) {
		List<Request> removed = new ArrayList<>();
		open.removeIf(r -> {
			if (r.sender().equals(player) || r.target().equals(player)) {
				removed.add(r);
				return true;
			}
			return false;
		});
		return removed;
	}

	public void clear() {
		open.clear();
	}

	public int size() {
		return open.size();
	}
}
