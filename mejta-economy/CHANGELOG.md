# MejtaEconomy changelog

## 1.1.0

- **Balance menu for staff:** `/eco menu` lists every account (online players first, with their balance); click one to give or take 1, 10, 100, 1,000 or 10,000 with one click, set an exact amount, reset to 0 (shift-click) or see the history. `/eco menu <player>` opens a player directly, also one who never joined. Needs `mejtaeconomy.admin`; every change is audited and logged like `/eco give|take|set`.

## 1.0.0

First release. Requires MejtaCore 1.8.0 or newer, below 2.0.0 (API version 6).

- Provides MejtaCore's shared economy (`MejtaCoreAPI.economy()`), which other addons use without depending on MejtaEconomy.
- Balances in exact minor units, a configurable currency (symbol, names, 0 to 4 decimals, starting balance, maximum balance).
- `/balance` (`/bal`), `/pay` (also to offline players), `/baltop` with the money in circulation, and `/eco history`.
- Staff: `/eco give|take|set|reset|status`, audited in MejtaCore's audit log.
- Players are told on joining what they received while offline.
- Accounts saved atomically once per tick with changes; a transaction log of every change with the addon and reason behind it.
