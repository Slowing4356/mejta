package com.mejta.mejtacrates;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import com.mejta.mejtacore.api.MejtaCoreAPI;
import com.mejta.mejtacore.api.audit.AuditActor;
import com.mejta.mejtacore.api.audit.AuditEntry;
import com.mejta.mejtacore.api.audit.AuditOutcome;
import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacrates.data.Crate;
import com.mejta.mejtacrates.data.CrateStore;
import com.mejta.mejtacrates.data.KeySerial;
import com.mejta.mejtacrates.data.PlayerKeys;
import com.mejta.mejtacrates.data.PlayerKeys.Opening;
import com.mejta.mejtacrates.data.Reward;
import com.mejta.mejtacrates.data.Roll;
import com.mejta.mejtacrates.menu.OpeningAnimation;
import com.mejta.mejtacrates.util.ItemJson;

/**
 * Keys, openings and rewards. Every method runs on the server thread.
 *
 * <h2>How keys and rewards are kept from being duplicated</h2>
 * <ul>
 * <li>Virtual keys are a count in the player's file, changed only by writes that are on the disk before
 * anything else happens.</li>
 * <li>Opening a crate takes the key and records the drawn reward as a pending opening in one write, before the
 * animation starts. The reward is handed out only after that opening is removed again, so it is handed out at
 * most once, also when the menu is closed early, the player disconnects or the server stops; an opening left
 * pending is finished at the next join.</li>
 * <li>Physical keys carry the serial of their batch. A batch opens at most as many crates as keys were issued in
 * it; a copied key found after that is taken away and reported to staff.</li>
 * </ul>
 */
public final class CrateService {
	public enum OpenOutcome {
		STARTED,
		NO_KEY,
		NO_REWARDS,
		/** Another opening of this player is still spinning. */
		BUSY,
		/** A physical key whose batch is unknown or of another crate. */
		INVALID_KEY,
		/** A physical key of a batch whose keys were all used: a copy. */
		COPIED_KEY,
		FAILED
	}

	private final CrateStore store;
	private final ConfigHandle config;
	private final Logger log;
	private final SecureRandom random = new SecureRandom();
	private final Map<UUID, OpeningAnimation> spinning = new ConcurrentHashMap<>();

	public CrateService(CrateStore store, ConfigHandle config, Logger log) {
		this.store = store;
		this.config = config;
		this.log = log;
	}

	public CrateStore store() {
		return store;
	}

	/** Money rewards can only be drawn while an economy is installed. */
	public static boolean available(Reward reward) {
		return !reward.paysMoney() || MejtaCoreAPI.economy().isAvailable();
	}

	public static List<Reward> pool(Crate crate) {
		return crate.eligible(CrateService::available);
	}

	// ---------------------------------------------------------------- giving and taking keys

	/**
	 * Gives virtual keys to a player who may be offline.
	 *
	 * @return whether they were saved
	 */
	public boolean giveVirtual(MinecraftServer server, AuditActor actor, UUID player, String name, Crate crate, int amount) {
		PlayerKeys keys = named(store.player(player, name), name).addKeys(crate.id(), amount);
		try {
			store.savePlayer(player, keys);
		} catch (IOException e) {
			auditGive(actor, player, name, crate, amount, "virtual", AuditOutcome.FAILED, e.toString());
			return false;
		}
		auditGive(actor, player, name, crate, amount, "virtual", AuditOutcome.SUCCESS, "now " + keys.keys(crate.id()));
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			tellKeys(online, crate, amount, "virtual");
		}
		return true;
	}

	/**
	 * Gives physical keys: to an online player's inventory at once (overflow drops at their feet), to an offline
	 * player at their next join.
	 */
	public boolean givePhysical(MinecraftServer server, AuditActor actor, UUID player, String name, Crate crate, int amount) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online == null) {
			PlayerKeys keys = named(store.player(player, name), name).addPhysical(crate.id(), amount);
			try {
				store.savePlayer(player, keys);
			} catch (IOException e) {
				auditGive(actor, player, name, crate, amount, "physical", AuditOutcome.FAILED, e.toString());
				return false;
			}
			auditGive(actor, player, name, crate, amount, "physical", AuditOutcome.SUCCESS, "given at the next join");
			return true;
		}
		Optional<List<ItemStack>> stacks = issue(server.registryAccess(), crate, online, amount, actor.name());
		if (stacks.isEmpty()) {
			auditGive(actor, player, name, crate, amount, "physical", AuditOutcome.FAILED, "the key serial could not be saved");
			return false;
		}
		handOut(online, stacks.get());
		auditGive(actor, player, name, crate, amount, "physical", AuditOutcome.SUCCESS, "");
		tellKeys(online, crate, amount, "physical");
		return true;
	}

	/** Keys for a name that has never joined; they move to the player at their first join. */
	public boolean giveByName(AuditActor actor, String name, Crate crate, int amount, boolean physical) {
		Optional<CrateStore.NamedKeys> existing = store.byName(name);
		PlayerKeys keys = existing.map(CrateStore.NamedKeys::keys).orElseGet(() -> PlayerKeys.empty(name));
		keys = physical ? keys.addPhysical(crate.id(), amount) : keys.addKeys(crate.id(), amount);
		String token = existing.map(CrateStore.NamedKeys::token).orElseGet(() -> newId(8));
		String kind = physical ? "physical" : "virtual";
		try {
			store.saveByName(name, new CrateStore.NamedKeys(token, keys));
		} catch (IOException e) {
			auditGive(actor, null, name, crate, amount, kind, AuditOutcome.FAILED, e.toString());
			return false;
		}
		auditGive(actor, null, name, crate, amount, kind, AuditOutcome.SUCCESS, "player has never joined; given at their first join");
		return true;
	}

	/** @return how many virtual keys were taken, or -1 if the change could not be saved */
	public int take(MinecraftServer server, AuditActor actor, UUID player, String name, Crate crate, int amount) {
		PlayerKeys keys = store.player(player, name);
		int taken = Math.min(amount, keys.keys(crate.id()));
		if (taken == 0) {
			return 0;
		}
		try {
			store.savePlayer(player, keys.addKeys(crate.id(), -taken));
		} catch (IOException e) {
			audit(AuditEntry.builder(MejtaCrates.ID, "keys.take").actor(actor).target(player, name).outcome(AuditOutcome.FAILED)
					.detail("crate", crate.id()).detail("amount", amount).detail("error", e.toString()));
			return -1;
		}
		audit(AuditEntry.builder(MejtaCrates.ID, "keys.take").actor(actor).target(player, name).outcome(AuditOutcome.SUCCESS)
				.detail("crate", crate.id()).detail("amount", taken));
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null && config.get(CrateSettings.NOTIFY_KEYS)) {
			online.sendSystemMessage(Component.literal(taken + " " + crate.name() + " key(s) were taken from you.").withStyle(ChatFormatting.GRAY));
		}
		return taken;
	}

	/** Makes a new batch of {@code amount} physical keys; empty if its serial could not be saved. */
	private Optional<List<ItemStack>> issue(HolderLookup.Provider registries, Crate crate, ServerPlayer owner, int amount, String by) {
		String serial = newId(16);
		try {
			store.saveSerial(new KeySerial(serial, crate.id(), amount, 0, owner.getUUID(), owner.getGameProfile().name(), by,
					System.currentTimeMillis()));
		} catch (IOException e) {
			log.error("Could not save key serial {}", serial, e);
			return Optional.empty();
		}
		ItemStack key = KeyItems.create(registries, crate, serial);
		List<ItemStack> stacks = new ArrayList<>();
		int left = amount;
		while (left > 0) {
			int n = Math.min(left, key.getMaxStackSize());
			stacks.add(key.copyWithCount(n));
			left -= n;
		}
		return Optional.of(stacks);
	}

	private void tellKeys(ServerPlayer player, Crate crate, int amount, String kind) {
		if (config.get(CrateSettings.NOTIFY_KEYS)) {
			player.sendSystemMessage(Component.literal("You received " + amount + " ").withStyle(ChatFormatting.GREEN)
					.append(CrateTexts.name(crate)).append(Component.literal(" key" + (amount == 1 ? "" : "s")
							+ (kind.equals("virtual") ? ". Open it with /crates." : ".")).withStyle(ChatFormatting.GREEN)));
		}
	}

	private void auditGive(AuditActor actor, UUID player, String name, Crate crate, int amount, String kind, AuditOutcome outcome, String note) {
		AuditEntry.Builder entry = AuditEntry.builder(MejtaCrates.ID, "keys.give").actor(actor).outcome(outcome)
				.detail("crate", crate.id()).detail("amount", amount).detail("kind", kind);
		if (player != null) {
			entry.target(player, name);
		} else {
			entry.target("name " + name);
		}
		if (!note.isEmpty()) {
			entry.detail("note", note);
		}
		audit(entry);
		if (outcome != AuditOutcome.SUCCESS) {
			log.error("Could not give {} {} key(s) of {} to {}: {}", amount, kind, crate.id(), name, note);
		}
	}

	private static void audit(AuditEntry.Builder entry) {
		MejtaCoreAPI.audit().record(entry.build(), CratePermissions.ALERTS);
	}

	// ---------------------------------------------------------------- opening

	public boolean isSpinning(ServerPlayer player) {
		return spinning.containsKey(player.getUUID());
	}

	/** Opens {@code crate} with one of the player's virtual keys. */
	public OpenOutcome openVirtual(ServerPlayer player, Crate crate, boolean animate) {
		if (isSpinning(player)) {
			return OpenOutcome.BUSY;
		}
		List<Reward> pool = pool(crate);
		if (pool.isEmpty()) {
			return OpenOutcome.NO_REWARDS;
		}
		String name = player.getGameProfile().name();
		PlayerKeys keys = named(store.player(player.getUUID(), name), name);
		if (keys.keys(crate.id()) <= 0) {
			return OpenOutcome.NO_KEY;
		}
		Opening opening = draw(crate, pool, "virtual");
		try {
			store.savePlayer(player.getUUID(), keys.addKeys(crate.id(), -1).withOpening(opening));
		} catch (IOException e) {
			log.error("Could not save the opening of {} by {}", crate.id(), name, e);
			return OpenOutcome.FAILED;
		}
		start(player, crate, pool, opening, animate);
		return OpenOutcome.STARTED;
	}

	/** Opens the crate of the physical key in the player's {@code hand}, using one key of the stack. */
	public OpenOutcome openPhysical(ServerPlayer player, InteractionHand hand, boolean animate) {
		ItemStack stack = player.getItemInHand(hand);
		Optional<KeyItems.KeyInfo> info = KeyItems.read(stack);
		if (info.isEmpty()) {
			return OpenOutcome.NO_KEY;
		}
		Optional<Crate> crate = store.crate(info.get().crate());
		if (crate.isEmpty()) {
			return OpenOutcome.INVALID_KEY;
		}
		if (isSpinning(player)) {
			return OpenOutcome.BUSY;
		}
		List<Reward> pool = pool(crate.get());
		if (pool.isEmpty()) {
			return OpenOutcome.NO_REWARDS;
		}
		Optional<KeySerial> serial = store.serial(info.get().serial());
		if (serial.isEmpty() || !serial.get().crate().equals(crate.get().id())) {
			confiscate(player, stack, crate.get(), info.get().serial(), "unknown serial");
			return OpenOutcome.INVALID_KEY;
		}
		if (!serial.get().hasUnused()) {
			confiscate(player, stack, crate.get(), info.get().serial(), "all " + serial.get().issued() + " key(s) of the batch were already used");
			return OpenOutcome.COPIED_KEY;
		}
		Opening opening = draw(crate.get(), pool, "physical:" + serial.get().serial());
		String name = player.getGameProfile().name();
		try {
			store.saveSerial(serial.get().withUsed(serial.get().used() + 1));
		} catch (IOException e) {
			log.error("Could not save key serial {}", serial.get().serial(), e);
			return OpenOutcome.FAILED;
		}
		try {
			store.savePlayer(player.getUUID(), named(store.player(player.getUUID(), name), name).withOpening(opening));
		} catch (IOException e) {
			log.error("Could not save the opening of {} by {}", crate.get().id(), name, e);
			try {
				store.saveSerial(serial.get()); // give the key its use back
			} catch (IOException again) {
				log.error("Could not restore key serial {}: one key of it is lost", serial.get().serial(), again);
			}
			return OpenOutcome.FAILED;
		}
		stack.shrink(1);
		start(player, crate.get(), pool, opening, animate);
		return OpenOutcome.STARTED;
	}

	private void confiscate(ServerPlayer player, ItemStack stack, Crate crate, String serial, String why) {
		int count = stack.getCount();
		stack.setCount(0);
		player.sendSystemMessage(Component.literal("This key is not valid and was taken away. Ask staff if you think this is wrong.")
				.withStyle(ChatFormatting.RED));
		audit(AuditEntry.builder(MejtaCrates.ID, "keys.copied").actor(MejtaCoreAPI.actor(player)).outcome(AuditOutcome.DENIED)
				.target(player.getUUID(), player.getGameProfile().name()).detail("crate", crate.id()).detail("serial", serial)
				.detail("count", count).detail("reason", why));
		log.warn("Took {} copied or unknown key(s) of {} (serial {}) from {}: {}", count, crate.id(), serial, player.getGameProfile().name(), why);
	}

	private Opening draw(Crate crate, List<Reward> pool, String key) {
		Reward reward = pool.get(Roll.pick(pool, random));
		return new Opening(newId(12), crate.id(), crate.name(), reward, System.currentTimeMillis(), key);
	}

	private void start(ServerPlayer player, Crate crate, List<Reward> pool, Opening opening, boolean animate) {
		if (animate && config.get(CrateSettings.ANIMATION)) {
			OpeningAnimation animation = new OpeningAnimation(this, player, crate, pool, opening, config.get(CrateSettings.ANIMATION_TICKS),
					config.get(CrateSettings.SOUNDS), random);
			spinning.put(player.getUUID(), animation);
			if (animation.open()) {
				return;
			}
			spinning.remove(player.getUUID());
		}
		finish(player, opening.id());
	}

	/** Called by the animation once it is over, however it ended. */
	public void animationEnded(ServerPlayer player, OpeningAnimation animation) {
		spinning.remove(player.getUUID(), animation);
	}

	/**
	 * Hands out the reward of a pending opening, once: the opening is removed on disk first.
	 *
	 * @return whether this call handed it out
	 */
	public boolean finish(ServerPlayer player, String openingId) {
		String name = player.getGameProfile().name();
		PlayerKeys keys = store.player(player.getUUID(), name);
		Optional<Opening> opening = keys.opening(openingId);
		if (opening.isEmpty()) {
			return false;
		}
		try {
			store.savePlayer(player.getUUID(), keys.withoutOpening(openingId));
		} catch (IOException e) {
			log.error("Could not save the end of opening {} of {}; it is retried at their next join", openingId, name, e);
			player.sendSystemMessage(Component.literal("Your reward could not be saved; you will get it when you join again.")
					.withStyle(ChatFormatting.RED));
			return false;
		}
		deliver(player, opening.get());
		return true;
	}

	private void deliver(ServerPlayer player, Opening opening) {
		MinecraftServer server = player.level().getServer();
		HolderLookup.Provider registries = server.registryAccess();
		Reward reward = opening.reward();
		String name = player.getGameProfile().name();
		String rewardName = CrateTexts.rewardName(registries, reward);
		List<String> problems = new ArrayList<>();

		List<ItemStack> items = new ArrayList<>();
		for (var json : reward.items()) {
			Optional<ItemStack> stack = ItemJson.decode(registries, json);
			if (stack.isPresent() && !stack.get().isEmpty()) {
				items.add(stack.get());
			} else {
				problems.add("unreadable item " + json);
			}
		}
		handOut(player, items);
		for (String command : reward.commands()) {
			String line = command.replace("{player}", name).replace("{uuid}", player.getUUID().toString()).replace("{crate}", opening.crate());
			try {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), line);
			} catch (RuntimeException e) {
				problems.add("command failed: " + line + " (" + e + ")");
			}
		}
		if (reward.paysMoney()) {
			EconomyResult paid = MejtaCoreAPI.economy().deposit(player.getUUID(), reward.money(), MejtaCrates.ID, "crate reward: " + opening.crateName());
			if (!paid.success()) {
				problems.add("money not paid: " + paid.status() + " " + paid.message());
				player.sendSystemMessage(Component.literal("The money of this reward could not be paid (" + paid.message()
						+ "). Staff have been told.").withStyle(ChatFormatting.RED));
			}
		}

		Optional<Crate> crate = store.crate(opening.crate());
		Component crateName = crate.<Component>map(CrateTexts::name).orElseGet(() -> Component.literal(opening.crateName()).withStyle(ChatFormatting.GOLD));
		player.sendSystemMessage(Component.literal("You won ").withStyle(ChatFormatting.GREEN)
				.append(Component.literal(rewardName).withStyle(ChatFormatting.AQUA))
				.append(Component.literal(" from ").withStyle(ChatFormatting.GREEN)).append(crateName)
				.append(Component.literal("!").withStyle(ChatFormatting.GREEN)));
		if (shouldBroadcast(reward, crate)) {
			server.getPlayerList().broadcastSystemMessage(Component.literal(name + " won ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(rewardName).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" from ").withStyle(ChatFormatting.YELLOW)).append(crateName)
					.append(Component.literal("!").withStyle(ChatFormatting.YELLOW)), false);
		}

		if (!problems.isEmpty() || config.get(CrateSettings.AUDIT_OPENINGS)) {
			AuditEntry.Builder entry = AuditEntry.builder(MejtaCrates.ID, "crate.open").actor(MejtaCoreAPI.actor(player))
					.target(player.getUUID(), name).outcome(problems.isEmpty() ? AuditOutcome.SUCCESS : AuditOutcome.FAILED)
					.detail("crate", opening.crate()).detail("reward", rewardName).detail("key", opening.key()).detail("opening", opening.id());
			if (!problems.isEmpty()) {
				entry.detail("problems", String.join("; ", problems));
				MejtaCoreAPI.audit().record(entry.build(), CratePermissions.ALERTS);
				log.error("Opening {} of {} by {} had problems: {}", opening.id(), opening.crate(), name, problems);
			} else {
				MejtaCoreAPI.audit().record(entry.build());
			}
		}
	}

	private boolean shouldBroadcast(Reward reward, Optional<Crate> crate) {
		if (reward.broadcast().isPresent()) {
			return reward.broadcast().get();
		}
		double threshold = config.get(CrateSettings.BROADCAST_CHANCE) / 100.0;
		if (threshold <= 0 || crate.isEmpty()) {
			return false;
		}
		List<Reward> pool = pool(crate.get());
		double total = Roll.totalWeight(pool);
		return total > 0 && reward.weight() / total <= threshold + 1e-12;
	}

	/** Into the inventory; what does not fit drops at the player's feet. */
	public static void handOut(ServerPlayer player, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			ItemStack copy = stack.copy();
			player.getInventory().add(copy);
			if (!copy.isEmpty()) {
				player.drop(copy, false);
			}
		}
	}

	// ---------------------------------------------------------------- joining

	/** Moves keys given by name, hands out physical keys given while offline and finishes pending openings. */
	public void onJoin(ServerPlayer player) {
		UUID id = player.getUUID();
		String name = player.getGameProfile().name();
		MinecraftServer server = player.level().getServer();
		AuditActor system = AuditActor.system(MejtaCrates.ID);

		if (CrateStore.PLAYER_NAME.matcher(name).matches()) {
			Optional<CrateStore.NamedKeys> byName = store.byName(name);
			if (byName.isPresent()) {
				PlayerKeys keys = named(store.player(id, name), name);
				if (!keys.hasClaimed(byName.get().token())) {
					try {
						store.savePlayer(id, keys.merge(byName.get().keys(), byName.get().token()));
						audit(AuditEntry.builder(MejtaCrates.ID, "keys.claim").actor(system).target(id, name).outcome(AuditOutcome.SUCCESS)
								.detail("virtual", byName.get().keys().keys()).detail("physical", byName.get().keys().physical()));
					} catch (IOException e) {
						log.error("Could not move the keys given to the name {} to their player file", name, e);
						byName = Optional.empty();
					}
				}
				if (byName.isPresent()) {
					store.deleteByName(name);
					for (Map.Entry<String, Integer> e : byName.get().keys().keys().entrySet()) {
						store.crate(e.getKey()).ifPresent(c -> tellKeys(player, c, e.getValue(), "virtual"));
					}
				}
			}
		}

		PlayerKeys keys = named(store.player(id, name), name);
		if (!keys.physical().isEmpty()) {
			List<ItemStack> stacks = new ArrayList<>();
			List<String> given = new ArrayList<>();
			for (Map.Entry<String, Integer> e : keys.physical().entrySet()) {
				Optional<Crate> crate = store.crate(e.getKey());
				if (crate.isEmpty()) {
					log.warn("{} had {} physical key(s) of the deleted crate {} waiting; they are dropped", name, e.getValue(), e.getKey());
					continue;
				}
				Optional<List<ItemStack>> batch = issue(server.registryAccess(), crate.get(), player, e.getValue(), "offline give");
				if (batch.isEmpty()) {
					return; // nothing changed: tried again at the next join
				}
				stacks.addAll(batch.get());
				given.add(e.getValue() + " " + crate.get().name());
			}
			try {
				store.savePlayer(id, keys.withoutPhysical());
			} catch (IOException e) {
				log.error("Could not save that {}'s waiting physical keys were handed out; tried again at the next join", name, e);
				return;
			}
			handOut(player, stacks);
			if (!given.isEmpty() && config.get(CrateSettings.NOTIFY_KEYS)) {
				player.sendSystemMessage(Component.literal("You received crate keys while you were away: " + String.join(", ", given) + ".")
						.withStyle(ChatFormatting.GREEN));
			}
		} else if (!keys.name().equals(store.player(id, name).name())) {
			try {
				store.savePlayer(id, keys);
			} catch (IOException ignored) {
				// only the name; saved the next time
			}
		}

		for (Opening opening : store.player(id, name).pending()) {
			player.sendSystemMessage(Component.literal("Finishing a crate you opened before:").withStyle(ChatFormatting.GRAY));
			finish(player, opening.id());
		}
		if (keys.totalKeys() > 0 && config.get(CrateSettings.NOTIFY_KEYS)) {
			player.sendSystemMessage(Component.literal("You have " + keys.totalKeys() + " crate key(s). Open them with /crates.")
					.withStyle(ChatFormatting.GOLD));
		}
	}

	/** Stops every animation (the server is stopping); their openings stay pending until the next join. */
	public void stopAll() {
		for (OpeningAnimation animation : List.copyOf(spinning.values())) {
			animation.abandon();
		}
		spinning.clear();
	}

	private static PlayerKeys named(PlayerKeys keys, String name) {
		return name == null || name.isEmpty() || keys.name().equals(name) ? keys : keys.withName(name);
	}

	private String newId(int hexDigits) {
		byte[] bytes = new byte[(hexDigits + 1) / 2];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes).substring(0, hexDigits);
	}
}
