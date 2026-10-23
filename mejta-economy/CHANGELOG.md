# MejtaEconomy changelog

## 1.0.0

First release. Requires MejtaCore 1.8.0 or newer, below 2.0.0 (API version 6).

- Provides MejtaCore's shared economy (`MejtaCoreAPI.economy()`), which other addons use without depending on MejtaEconomy.
- Balances in exact minor units, a configurable currency (symbol, names, 0 to 4 decimals, starting balance, maximum balance).
- `/balance` (`/bal`), `/pay` (also to offline players), `/baltop` with the money in circulation, and `/eco history`.
- Staff: `/eco give|take|set|reset|status`, audited in MejtaCore's audit log.
- Players are told on joining what they received while offline.
- Accounts saved atomically once per tick with changes; a transaction log of every change with the addon and reason behind it.
