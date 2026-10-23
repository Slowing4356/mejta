package com.mejta.mejtaeconomy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import com.mejta.mejtacore.api.config.ConfigHandle;
import com.mejta.mejtacore.api.economy.BalanceEntry;
import com.mejta.mejtacore.api.economy.EconomyProvider;
import com.mejta.mejtacore.api.economy.EconomyResult;
import com.mejta.mejtacore.api.storage.DataStore;
import com.mejta.mejtacore.api.storage.RecordLog;
import com.mejta.mejtaeconomy.data.Ledger;
import com.mejta.mejtaeconomy.data.MoneyFormat;
import com.mejta.mejtaeconomy.data.WorldKeys;

/**
 * The accounts behind MejtaCore's economy. Loads the ledger when a server starts, saves it at the end of
 * every tick in which it changed (one atomic file, so a transfer is saved whole or not at all) and when
 * the server stops, and logs every change.
 *
 * <p>Between servers (and if loading failed) there is no ledger: every change then fails and nothing
 * is saved, so a broken file is never overwritten with empty accounts.
 */
public final class EcoProvider implements EconomyProvider {
	static final String KEY = "accounts";

	private final ConfigHandle config;
	private final DataStore store;
	private final RecordLog transactions;
	private final Logger log;

	private MinecraftServer server;
	private Ledger ledger;
	private String key = KEY;
	private boolean dirty;
	private volatile MoneyFormat format;

	public EcoProvider(ConfigHandle config, DataStore store, RecordLog transactions, Logger log) {
		this.config = config;
		this.store = store;
		this.transactions = transactions;
		this.log = log;
		this.format = readFormat(config.get(EconomySettings.DECIMALS));
		config.addChangeListener(this::settingsChanged);
	}

	// ---------------------------------------------------------------- lifecycle

	public void serverStarting(MinecraftServer server) {
		this.server = server;
		this.key = server.isDedicatedServer() ? KEY : worldKey(server);
		int digits = config.get(EconomySettings.DECIMALS);
		Ledger loaded = new Ledger(digits);
		try {
			Optional<JsonObject> saved = store.read(key);
			if (saved.isPresent()) {
				int savedDigits = saved.get().has("digits") ? saved.get().get("digits").getAsInt() : digits;
				int skipped = loaded.load(saved.get());
				if (skipped > 0) {
					log.warn("Skipped {} unreadable account(s) in {}", skipped, key);
				}
				if (savedDigits != digits) {
					log.warn("currency.decimals changed from {} to {}: converted {} balance(s)", savedDigits, digits, loaded.size());
					dirty = true;
				}
			}
		} catch (RuntimeException e) {
			log.error("Could not load the accounts ({}); the economy is off until this is fixed and the server restarts", key, e);
			ledger = null;
			return;
		}
		ledger = loaded;
		format = readFormat(digits);
		applyLimits();
		log.info("Loaded {} account(s), {} in circulation", ledger.size(), format(ledger.total()));
	}

	/** Saves; other addons may still pay out while the server stops. */
	public void serverStopping() {
		save();
	}

	/** Saves, waits for the disk and forgets the ledger. */
	public void serverStopped() {
		save();
		store.flush().join();
		transactions.flush().join();
		ledger = null;
		server = null;
	}

	/** End of every server tick: writes the ledger if it changed. */
	public void tick() {
		if (dirty) {
			save();
		}
	}

	private void save() {
		if (ledger != null && dirty) {
			store.write(key, ledger.toJson());
			dirty = false;
		}
	}

	/** On join: creates the account, updates the name, returns the money received while offline. */
	public long joined(ServerPlayer player) {
		if (ledger == null) {
			return 0;
		}
		boolean created = ledger.ensure(player.getUUID(), Optional.of(player.getGameProfile().name()));
		if (created) {
			logChange("open", player.getUUID(), null, ledger.balance(player.getUUID()), ledger.balance(player.getUUID()), "mejtaeconomy", "first join");
		}
		long away = ledger.takeAway(player.getUUID());
		dirty = true;
		return away;
	}

	// ---------------------------------------------------------------- EconomyProvider

	@Override
	public int fractionDigits() {
		return ledger != null ? ledger.digits() : format.digits();
	}

	@Override
	public String currencySingular() {
		return format.singular();
	}

	@Override
	public String currencyPlural() {
		return format.plural();
	}

	@Override
	public String format(long amount) {
		return format.format(amount);
	}

	@Override
	public boolean hasAccount(UUID player) {
		return ledger != null && ledger.has(player);
	}

	@Override
	public boolean ensureAccount(UUID player, Optional<String> name) {
		if (ledger == null) {
			return false;
		}
		if (ledger.ensure(player, name)) {
			dirty = true;
			logChange("open", player, null, ledger.balance(player), ledger.balance(player), "mejtaeconomy", "account created");
		}
		return true;
	}

	@Override
	public long balance(UUID player) {
		return ledger == null ? 0 : ledger.balance(player);
	}

	@Override
	public EconomyResult deposit(UUID player, long amount, String source, String reason) {
		if (ledger == null) {
			return notLoaded();
		}
		EconomyResult r = ledger.deposit(player, amount);
		if (r.success()) {
			dirty = true;
			if (!online(player)) {
				ledger.addAway(player, amount);
			}
			logChange("deposit", player, null, amount, r.balance(), source, reason);
		}
		return r;
	}

	@Override
	public EconomyResult withdraw(UUID player, long amount, String source, String reason) {
		if (ledger == null) {
			return notLoaded();
		}
		EconomyResult r = ledger.withdraw(player, amount);
		if (r.success()) {
			dirty = true;
			logChange("withdraw", player, null, amount, r.balance(), source, reason);
		}
		return r;
	}

	@Override
	public EconomyResult transfer(UUID from, UUID to, long amount, String source, String reason) {
		if (ledger == null) {
			return notLoaded();
		}
		EconomyResult r = ledger.transfer(from, to, amount);
		if (r.success()) {
			dirty = true;
			if (!online(to)) {
				ledger.addAway(to, amount);
			}
			logChange("transfer", from, to, amount, r.balance(), source, reason);
		}
		return r;
	}

	@Override
	public List<BalanceEntry> top(int limit) {
		return ledger == null ? List.of() : ledger.top(limit);
	}

	// ---------------------------------------------------------------- for commands

	/** The loaded ledger, or empty between servers or after a failed load. */
	public Optional<Ledger> ledger() {
		return Optional.ofNullable(ledger);
	}

	/** Whole units from a setting to minor units in the current currency. */
	public long minor(double units) {
		return ledger != null ? ledger.toMinor(units) : new Ledger(format.digits()).toMinor(units);
	}

	public RecordLog transactions() {
		return transactions;
	}

	public String nameOf(UUID player) {
		return ledger == null ? player.toString() : ledger.get(player).map(Ledger.Account::name).orElse(player.toString().substring(0, 8));
	}

	// ---------------------------------------------------------------- internals

	private void settingsChanged() {
		// The number of decimals only changes on restart, together with the saved balances.
		format = readFormat(fractionDigits());
		applyLimits();
	}

	private void applyLimits() {
		if (ledger != null) {
			ledger.setMaxBalance(ledger.toMinor(config.get(EconomySettings.MAX_BALANCE)));
			ledger.setStartingBalance(ledger.toMinor(config.get(EconomySettings.STARTING_BALANCE)));
		}
	}

	private MoneyFormat readFormat(int digits) {
		return new MoneyFormat(digits, config.get(EconomySettings.SYMBOL), config.get(EconomySettings.SYMBOL_POSITION),
				config.get(EconomySettings.SINGULAR), config.get(EconomySettings.PLURAL), config.get(EconomySettings.SHOW_NAME));
	}

	private boolean online(UUID player) {
		return server != null && server.getPlayerList().getPlayer(player) != null;
	}

	private static EconomyResult notLoaded() {
		return EconomyResult.failure(EconomyResult.Status.FAILED, 0, "The economy is not loaded; see the server log.");
	}

	private void logChange(String type, UUID player, UUID other, long amount, long balance, String source, String reason) {
		if (!config.get(EconomySettings.LOG_TRANSACTIONS)) {
			return;
		}
		JsonObject r = new JsonObject();
		r.addProperty("type", type);
		r.addProperty("player", player.toString());
		r.addProperty("name", nameOf(player));
		if (other != null) {
			r.addProperty("to", other.toString());
			r.addProperty("to_name", nameOf(other));
			r.addProperty("to_balance", ledger.balance(other));
		}
		r.addProperty("amount", amount);
		r.addProperty("balance", balance);
		r.addProperty("digits", ledger.digits());
		r.addProperty("source", source);
		r.addProperty("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
		transactions.append(r);
	}

	private static String worldKey(MinecraftServer server) {
		Path players = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toAbsolutePath().normalize();
		Path world = players.getParent() != null ? players.getParent() : players;
		Path folder = world.getFileName();
		return WorldKeys.scoped(KEY, folder != null ? folder.toString() : "world", world.toString());
	}
}
