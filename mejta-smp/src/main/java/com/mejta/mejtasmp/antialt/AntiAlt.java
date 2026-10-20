package com.mejta.mejtasmp.antialt;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.net.InetAddresses;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.notify.Notification;
import com.mejta.mejtacore.api.notify.Severity;
import com.mejta.mejtasmp.MejtaSMP;
import com.mejta.mejtasmp.SmpPermissions;
import com.mejta.mejtasmp.SmpSettings;
import com.mejta.mejtasmp.util.ScopedDataStore;
import com.mejta.mejtasmp.util.Templates;

/**
 * The account limit ({@code antialt.*}): how many accounts may be online at once from one internet address,
 * and optionally how many different accounts may ever join from it.
 *
 * <p>Addresses are never stored, shown or logged. Each is turned into a salted SHA-256 hash; the salt is
 * random per server and kept in MejtaSMP's data store. Only the hash and the accounts seen with it are
 * kept, and only while a limit on different accounts is set. Loopback addresses (the server's own machine)
 * are never limited. Server thread only.
 */
public final class AntiAlt {
	private static final String SALT_KEY = "antialt/salt";
	private static final String ADDRESS_FOLDER = "antialt/addresses";

	private final ConfigHandle config;
	private final ScopedDataStore store;
	/** Online players by address hash, only for players counted by the limit. */
	private final Map<UUID, String> online = new ConcurrentHashMap<>();
	private volatile byte[] salt;

	public AntiAlt(ConfigHandle config, ScopedDataStore store) {
		this.config = config;
		this.store = store;
	}

	/** A player joined: refuses them if the limit is reached. Returns whether they may stay. */
	public boolean onJoin(ServerPlayer player) {
		if (!config.get(SmpSettings.ANTIALT) || MejtaCoreAPI.hasPermission(player, SmpPermissions.ANTIALT_BYPASS)) {
			return true;
		}
		InetAddress ip;
		try {
			ip = InetAddresses.forString(player.getIpAddress());
		} catch (IllegalArgumentException e) {
			return true; // not a network connection (e.g. a test player)
		}
		if (ip.isLoopbackAddress()) {
			return true;
		}
		String hash = hash(ip.getHostAddress());
		UUID id = player.getUUID();
		List<String> sharing = new ArrayList<>();
		for (Map.Entry<UUID, String> e : online.entrySet()) {
			if (e.getValue().equals(hash) && !e.getKey().equals(id)) {
				ServerPlayer other = player.level().getServer().getPlayerList().getPlayer(e.getKey());
				if (other != null) {
					sharing.add(other.getGameProfile().name());
				}
			}
		}
		int maxAccounts = config.get(SmpSettings.ANTIALT_MAX_ACCOUNTS);
		Set<UUID> known = maxAccounts > 0 ? known(hash) : Set.of();
		AltRules.Decision decision = AltRules.decide(id, sharing.size(), config.get(SmpSettings.ANTIALT_MAX_ONLINE), known, maxAccounts);
		if (decision != AltRules.Decision.ALLOW) {
			refuse(player, decision, sharing, known.size());
			return false;
		}
		online.put(id, hash);
		if (maxAccounts > 0 && !known.contains(id)) {
			Set<UUID> updated = new LinkedHashSet<>(known);
			updated.add(id);
			JsonObject json = new JsonObject();
			JsonArray accounts = new JsonArray();
			updated.forEach(u -> accounts.add(u.toString()));
			json.add("accounts", accounts);
			store.write(ADDRESS_FOLDER + "/" + hash, json);
		}
		return true;
	}

	public void onLeave(UUID player) {
		online.remove(player);
	}

	public void reset() {
		online.clear();
		salt = null;
	}

	public int counted() {
		return online.size();
	}

	private void refuse(ServerPlayer player, AltRules.Decision decision, List<String> sharing, int known) {
		String name = player.getGameProfile().name();
		String reason = decision == AltRules.Decision.TOO_MANY_ONLINE
				? "already online from the same connection: " + String.join(", ", sharing)
				: known + " other account(s) already joined from the same connection";
		player.connection.disconnect(Component.literal(Templates.render(config.get(SmpSettings.ANTIALT_MESSAGE), Map.of())));
		MejtaSMP.log().info("Account limit refused {} ({})", name, reason);
		MejtaCoreAPI.notifications().publish(Notification.builder(MejtaSMP.ID, "mejtasmp.antialt")
				.severity(Severity.WARNING)
				.title("Account limit")
				.message(name + " was refused: " + reason + ".")
				.field("player", name)
				.inGameAudience(SmpPermissions.ANTIALT_ALERTS)
				.build());
	}

	private Set<UUID> known(String hash) {
		Set<UUID> accounts = new LinkedHashSet<>();
		store.read(ADDRESS_FOLDER + "/" + hash).ifPresent(json -> {
			if (json.get("accounts") instanceof JsonArray array) {
				for (JsonElement e : array) {
					try {
						accounts.add(UUID.fromString(e.getAsString()));
					} catch (RuntimeException ignored) {
						// a damaged entry is skipped
					}
				}
			}
		});
		return accounts;
	}

	/** Salted SHA-256 of an address, as 32 hex characters. */
	String hash(String address) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(salt());
			digest.update(address.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing", e);
		}
	}

	private byte[] salt() {
		byte[] s = salt;
		if (s != null) {
			return s;
		}
		s = store.read(SALT_KEY).filter(json -> json.has("salt")).map(json -> {
			try {
				return Base64.getDecoder().decode(json.get("salt").getAsString());
			} catch (RuntimeException e) {
				return null;
			}
		}).orElse(null);
		if (s == null || s.length < 16) {
			s = new byte[32];
			new SecureRandom().nextBytes(s);
			JsonObject json = new JsonObject();
			json.addProperty("salt", Base64.getEncoder().encodeToString(s));
			store.write(SALT_KEY, json);
		}
		salt = s;
		return s;
	}
}
