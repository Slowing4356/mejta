# MejtaEconomy

MejtaEconomy (mod ID `mejtaeconomy`) gives a server running **MejtaCore** one shared economy, in the spirit of Vault plus a basic economy plugin:
- Every player has a balance. `/balance`, `/pay`, `/baltop`, and staff commands to give, take and set money.
- **Other addons use it through MejtaCore** (`MejtaCoreAPI.economy()`), never through MejtaEconomy itself: shops, crates and auction houses work with any economy addon that provides MejtaCore's economy, and stay installable on their own.
- **Exact money:** amounts are whole numbers of cents (or of whatever smallest unit you choose), never floating point, so prices always add up.
- **Safe:** a payment is all or nothing, balances never go below zero, and every change is written to a transaction log that staff can read in game.
- **Offline players:** they can be paid (for example when their auction sells), and are told on their next join how much they received while away.

It is server-side only: players need no mod.

| | |
|---|---|
| Version | 1.1.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.8.0 or newer, below 2.0.0 (API version 6) |
| Other libraries | None |

## Installing

1. Put `mejtacore-1.9.0.jar`, `mejtaeconomy-1.1.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. The settings file `config/mejta/mejtaeconomy.json` is created on first start; every setting can also be changed in game in MejtaCore's settings editor (`/mejta config mejtaeconomy`, or `/mejtaadmin config` without client mods).

Only one economy addon can be active. If another one registered first, MejtaEconomy fails to initialize and says so in `/mejtacore addons`.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/balance`, `/bal` | `mejtaeconomy.balance` | Your balance and rank |
| `/balance <player>` | `mejtaeconomy.balance.others` | Another player's balance, also offline players' |
| `/pay <player> <amount>` | `mejtaeconomy.pay` | Send money. The receiver is told, or told on their next join if offline (`pay.offline_players`) |
| `/baltop [page]` | `mejtaeconomy.baltop` | The richest players, the money in circulation and your rank |
| `/eco history` | `mejtaeconomy.history` | Your last 10 transactions: payments, sales, purchases, with the addon behind each |
| `/eco history <player>` | `mejtaeconomy.admin` | Anyone's last 10 transactions |
| `/eco menu [player]` | `mejtaeconomy.admin` | A chest menu to edit balances: every account (online players first), then per player buttons to give or take 1 to 10,000, set an exact amount, reset to 0 (shift-click) and see the history |
| `/eco give <player> <amount>` | `mejtaeconomy.admin` | Add money (creates the account of a player who has a known profile but never joined) |
| `/eco take <player> <amount>` | `mejtaeconomy.admin` | Remove money; refused if the player has less |
| `/eco set <player> <amount>` | `mejtaeconomy.admin` | Set the balance (`0` allowed) |
| `/eco reset <player>` | `mejtaeconomy.admin` | Set the balance to 0 |
| `/eco status` | `mejtaeconomy.admin` | Accounts, money in circulation, decimals and limit |

**Amounts** accept decimals and shortcuts: `25`, `12.50`, `2.5k` (2,500), `1m` (1,000,000). Staff changes are recorded in MejtaCore's audit log (`eco.give`, `eco.take`, `eco.set`) with the balance before and after.

Every command is also available under `/mejtaadmin mejtaeconomy`, in case another mod uses the name; server owners can switch top-level names off with `commands.disabled` in `mejtacore.json`.

## Permissions

Change their levels in `config/mejta/mejtacore.permissions.json`.

| Node | Default | Allows |
|---|---|---|
| `mejtaeconomy.balance` | everyone | `/balance` |
| `mejtaeconomy.balance.others` | everyone | `/balance <player>` |
| `mejtaeconomy.pay` | everyone | `/pay` |
| `mejtaeconomy.baltop` | everyone | `/baltop` |
| `mejtaeconomy.history` | everyone | `/eco history` (own) |
| `mejtaeconomy.admin` | admins (level 3) | `/eco menu`, `/eco give|take|set|reset|status`, anyone's history |

## Settings

`config/mejta/mejtaeconomy.json`, grouped in the settings editor by the part before the dot.

| Key | Default | What it does |
|---|---|---|
| `currency.symbol` | `$` | Currency symbol, at most 8 characters |
| `currency.symbol_position` | `BEFORE` | `BEFORE` ($12.50), `AFTER` (12.50$) or `NONE` |
| `currency.singular` / `currency.plural` | `coin` / `coins` | Currency names, used by other addons and with `currency.show_name` |
| `currency.show_name` | off | Write the name after amounts (`12.50 coins`) |
| `currency.decimals` | 2 | Digits after the decimal point, 0 to 4. **Takes effect on restart**, and saved balances are converted then (rounding down when there are fewer decimals) |
| `accounts.starting_balance` | 0 | Money a player gets on their first join |
| `accounts.max_balance` | 0 | Most money one account may hold; 0 = no limit |
| `pay.enabled` | on | `/pay` works |
| `pay.min_amount` | 0.01 | Smallest amount `/pay` accepts |
| `pay.offline_players` | on | Players can pay offline players who have joined before |
| `baltop.page_size` | 10 | Lines per `/baltop` page |
| `notify.away_summary` | on | Tell players on joining what they received while offline |
| `log.transactions` | on | Keep the transaction log (needed for `/eco history`) |

## Data

- **Accounts:** `mejta-data/mejtaeconomy/store/accounts.json`, one file for every account. It is replaced atomically at the end of each tick in which money moved, so a payment is always saved whole, and MejtaCore keeps three backups and recovers a damaged file from them. If the file cannot be read at all, the economy stays off (every payment is refused) instead of starting everyone at zero; fix or restore the file and restart.
- **Transactions:** `mejta-data/mejtaeconomy/transactions.jsonl` (and rotated `transactions.N.jsonl`, up to 10 files of 8 MiB): one line per change with the player, amount, new balance, the addon that asked for it and its reason.
- In singleplayer and LAN worlds, each world has its own accounts (`store/worlds/<world>/accounts.json`).

## For addon developers

Use MejtaCore's API, not MejtaEconomy: compile against MejtaCore 1.8.0 and declare `"mejtacore": ">=1.8.0 <2.0.0"`.

```java
EconomyService eco = MejtaCoreAPI.economy();
if (!eco.isAvailable()) {
    return; // no economy addon installed: hide money features
}
OptionalLong price = eco.parse("12.50");               // 1250 with 2 decimals
EconomyResult r = eco.transfer(buyer, seller, price.getAsLong(), "myshop", "bought 16 oak logs");
if (r.success()) {
    player.sendSystemMessage(Component.literal("Paid " + eco.format(price.getAsLong())));
} else {
    player.sendSystemMessage(Component.literal(r.message()));   // e.g. "Not enough money."
}
```

- Amounts are `long`s in minor units; `fractionDigits()`, `format()` and `currencyName()` describe the currency.
- `deposit`, `withdraw` and `transfer` return an `EconomyResult` with a `Status` (`SUCCESS`, `INSUFFICIENT_FUNDS`, `BALANCE_LIMIT`, `INVALID_AMOUNT`, `NO_ACCOUNT`, `NO_PROVIDER`, `FAILED`), the new balance and a message for players. Nothing changes unless it is `SUCCESS`.
- `deposit` works for offline players and creates their account; call `ensureAccount(uuid, Optional.of(name))` first to store their name.
- Pass your addon ID as `source` and a short `reason`; both appear in `/eco history`.
- `addListener` reports every change (for sidebars and leaderboards); `top(n)` gives the richest accounts.
- Call everything on the server thread.
