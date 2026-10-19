package com.mejta.mejtaqol.tpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtaqol.QolSettings;
import com.mejta.mejtaqol.data.Prefs;
import com.mejta.mejtaqol.location.Locations;
import com.mejta.mejtaqol.teleport.Features;
import com.mejta.mejtaqol.teleport.Teleports;
import com.mejta.mejtaqol.tpa.TpaRequests.Direction;
import com.mejta.mejtaqol.tpa.TpaRequests.Request;
import com.mejta.mejtaqol.util.Durations;
import com.mejta.mejtaqol.util.Msg;
import com.mejta.mejtaqol.util.Players;

/** Teleport requests between players: {@code /tpa}, {@code /tpahere} and the answers. Server thread only. */
public final class TpaService {
	static final String REQUEST_COOLDOWN = "tpa-request";

	private final ConfigHandle config;
	private final Teleports teleports;
	private final Prefs prefs;
	private final Function<UUID, ServerPlayer> online;
	private final TpaRequests requests = new TpaRequests();

	/** @param online finds an online player by UUID (null if offline) */
	public TpaService(ConfigHandle config, Teleports teleports, Prefs prefs, Function<UUID, ServerPlayer> online) {
		this.config = config;
		this.teleports = teleports;
		this.prefs = prefs;
		this.online = online;
	}

	public TpaRequests requests() {
		return requests;
	}

	/** {@code /tpa} ({@link Direction#TO_TARGET}) or {@code /tpahere} ({@link Direction#TO_SENDER}). */
	public boolean send(ServerPlayer sender, ServerPlayer target, Direction direction) {
		if (sender == target || sender.getUUID().equals(target.getUUID())) {
			sender.sendSystemMessage(Msg.error("You can't send a teleport request to yourself."));
			return false;
		}
		if (!prefs.acceptsTeleportRequests(target.getUUID()) && !teleports.bypasses(sender)) {
			sender.sendSystemMessage(Msg.error(Players.name(target) + " is not accepting teleport requests."));
			return false;
		}
		long now = System.currentTimeMillis();
		if (!teleports.bypasses(sender)) {
			long wait = teleports.cooldowns().remaining(sender.getUUID(), REQUEST_COOLDOWN, config.get(QolSettings.TPA_COOLDOWN), now);
			if (wait > 0) {
				sender.sendSystemMessage(Msg.error("You can send another request in " + Durations.format(wait) + "."));
				return false;
			}
		}
		if (direction == Direction.TO_TARGET) {
			// The sender travels: refuse now rather than after the other player accepts.
			Optional<Component> refused = teleports.refusal(sender, Features.TPA);
			if (refused.isPresent()) {
				sender.sendSystemMessage(refused.get());
				return false;
			}
		}
		int timeout = config.get(QolSettings.TPA_TIMEOUT);
		Optional<Request> replaced = requests.add(sender.getUUID(), Players.name(sender), target.getUUID(), Players.name(target), direction,
				now, timeout * 1000L);
		teleports.cooldowns().use(sender.getUUID(), REQUEST_COOLDOWN, now);

		String senderName = Players.name(sender);
		String targetName = Players.name(target);
		sender.sendSystemMessage(Msg.success((replaced.isPresent() ? "Request to " + targetName + " renewed" : "Request sent to " + targetName)
				+ ". It expires in " + timeout + " s. ").append(Msg.button("Cancel", "/tpacancel " + targetName, ChatFormatting.RED)));
		String ask = direction == Direction.TO_TARGET ? " wants to teleport to you. " : " asks you to teleport to them. ";
		target.sendSystemMessage(Msg.highlight(senderName).append(Msg.info(ask))
				.append(Msg.button("Accept", "/tpaccept " + senderName, ChatFormatting.GREEN))
				.append(Component.literal(" "))
				.append(Msg.button("Deny", "/tpdeny " + senderName, ChatFormatting.RED)));
		return true;
	}

	/** {@code /tpaccept [player]}. */
	public boolean accept(ServerPlayer target, Optional<String> senderName) {
		Optional<Request> taken = take(target, senderName);
		if (taken.isEmpty()) {
			return false;
		}
		Request r = taken.get();
		ServerPlayer sender = online.apply(r.sender());
		if (sender == null) {
			target.sendSystemMessage(Msg.error(r.senderName() + " is no longer online."));
			return false;
		}
		ServerPlayer traveller = r.direction() == Direction.TO_TARGET ? sender : target;
		ServerPlayer host = r.direction() == Direction.TO_TARGET ? target : sender;
		UUID hostId = host.getUUID();
		String hostName = Players.name(host);
		target.sendSystemMessage(Msg.success("Accepted " + r.senderName() + "'s request."));
		sender.sendSystemMessage(Msg.success(r.targetName() + " accepted your request."));
		boolean started = teleports.request(traveller, Features.TPA, Component.literal(hostName), t -> {
			ServerPlayer h = online.apply(hostId);
			if (h == null || h.isRemoved()) {
				return Teleports.Result.fail(hostName + " is no longer online.");
			}
			return Teleports.Result.to(Locations.of(h));
		});
		if (!started && host != traveller) {
			host.sendSystemMessage(Msg.error(Players.name(traveller) + " could not teleport."));
		}
		return started;
	}

	/** {@code /tpdeny [player]}. */
	public boolean deny(ServerPlayer target, Optional<String> senderName) {
		Optional<Request> taken = take(target, senderName);
		if (taken.isEmpty()) {
			return false;
		}
		Request r = taken.get();
		target.sendSystemMessage(Msg.info("Denied " + r.senderName() + "'s request."));
		ServerPlayer sender = online.apply(r.sender());
		if (sender != null) {
			sender.sendSystemMessage(Msg.error(r.targetName() + " denied your request."));
		}
		return true;
	}

	/** {@code /tpacancel [player]}: cancels the request to that player, or all of the sender's requests. */
	public int cancel(ServerPlayer sender, Optional<String> targetName) {
		List<Request> out = requests.outgoing(sender.getUUID()).stream()
				.filter(r -> targetName.isEmpty() || r.targetName().equalsIgnoreCase(targetName.get())).toList();
		if (out.isEmpty()) {
			sender.sendSystemMessage(Msg.error(targetName.map(n -> "You have no open request to " + n + ".")
					.orElse("You have no open teleport requests.")));
			return 0;
		}
		for (Request r : out) {
			requests.remove(r);
			ServerPlayer target = online.apply(r.target());
			if (target != null) {
				target.sendSystemMessage(Msg.info(r.senderName() + " cancelled their teleport request."));
			}
		}
		sender.sendSystemMessage(Msg.info("Cancelled " + out.size() + " request(s)."));
		return out.size();
	}

	/** {@code /tptoggle}: returns whether the player now accepts requests. */
	public boolean toggle(ServerPlayer player) {
		boolean accept = !prefs.acceptsTeleportRequests(player.getUUID());
		prefs.setAcceptsTeleportRequests(player.getUUID(), accept);
		player.sendSystemMessage(accept ? Msg.success("You now accept teleport requests.")
				: Msg.info("You now refuse teleport requests. Use /tptoggle again to accept them."));
		if (!accept) {
			for (Request r : requests.incoming(player.getUUID())) {
				requests.remove(r);
				ServerPlayer sender = online.apply(r.sender());
				if (sender != null) {
					sender.sendSystemMessage(Msg.error(r.targetName() + " is not accepting teleport requests."));
				}
			}
		}
		return accept;
	}

	private Optional<Request> take(ServerPlayer target, Optional<String> senderName) {
		requests.expire(System.currentTimeMillis());
		Optional<UUID> sender = senderName.flatMap(name -> requests.incoming(target.getUUID()).stream()
				.filter(r -> r.senderName().equalsIgnoreCase(name)).map(Request::sender).findFirst());
		if (senderName.isPresent() && sender.isEmpty()) {
			target.sendSystemMessage(Msg.error("You have no teleport request from " + senderName.get() + "."));
			return Optional.empty();
		}
		Optional<Request> r = requests.take(target.getUUID(), sender);
		if (r.isEmpty()) {
			target.sendSystemMessage(Msg.error("You have no teleport requests."));
		}
		return r;
	}

	/** Once a second: tells both sides about expired requests. */
	public void tick() {
		for (Request r : requests.expire(System.currentTimeMillis())) {
			ServerPlayer sender = online.apply(r.sender());
			if (sender != null) {
				sender.sendSystemMessage(Msg.info("Your teleport request to " + r.targetName() + " expired."));
			}
			ServerPlayer target = online.apply(r.target());
			if (target != null) {
				target.sendSystemMessage(Msg.info("The teleport request from " + r.senderName() + " expired."));
			}
		}
	}

	/** Requests from or to a player who left are dropped silently (they may be vanished staff). */
	public void onLeave(UUID player) {
		requests.removeAll(player);
	}

	public void reset() {
		requests.clear();
	}
}
