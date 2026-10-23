# Mejta v2026.10.6

All eleven Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **New:** MejtaEconomy 1.0.0 (balances and `/pay`), MejtaMarket 1.0.0 (auction house with buy orders and a market bot) and MejtaCrates 1.0.0 (crates and keys).
- **Updated:** MejtaCore 1.8.0, with the shared economy API the new mods use.
- **Unchanged since v2026.10.5:** MejtaAC, MejtaUtils, MejtaDiscord, MejtaPerf, MejtaCorpse, MejtaQoL and MejtaSMP.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.8.0 (updated) | Shared platform: settings with in-game editing (a settings editor with MejtaCore on the client, a chest menu for vanilla clients), permissions, notifications, secrets, data store, audit log, server menus (screens for players with MejtaCore, chests for everyone else), scheduler, metrics, shared AFK and combat status, and the shared economy API. Required by every other mod. | Server (client optional, for the `/mejta` menu, the settings editor and menu screens) |
| **MejtaAC** | 1.1.1 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.2 | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.2.0 | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour, with backups of the last deaths that staff can restore. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.1.1 | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.0.1 | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |
| **MejtaEconomy** | 1.0.0 (new) | Player balances, `/pay`, `/baltop`, staff commands and a transaction log. Provides MejtaCore's economy, which the market and crates use. | Server |
| **MejtaMarket** | 1.0.0 (new) | Auction house (`/ah`) with buy orders, a collection box, fees and a market bot that keeps trading going on quiet servers. Built so items can never be duplicated. | Server |
| **MejtaCrates** | 1.0.0 (new) | Crates opened with keys: weighted rewards of items, commands and money, a spinning opening animation and a preview. Staff and the console (donation stores) give keys, also to offline players. Built so keys and rewards can never be duplicated. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaEconomy, MejtaMarket and MejtaCrates need MejtaCore **1.8.0** or newer; MejtaQoL and MejtaSMP 1.5.0 or newer; the others 1.3.0 or newer, all below 2.0.0. MejtaCore 1.8.0 keeps working with addons built for the previous API. Addons never depend on each other directly, but **MejtaMarket needs an economy addon** (MejtaEconomy): without one the market stays closed. MejtaCrates works without one and leaves out money rewards. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.8.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars. Coming from v2026.10.5, replace `mejtacore-1.8.0.jar` and add the new mods you want: `mejtaeconomy-1.0.0.jar`, `mejtamarket-1.0.0.jar` (with MejtaEconomy), `mejtacrates-1.0.0.jar`. The other jars did not change.
2. Players with MejtaCore on their client can keep their 1.7.0 jar or update it; 1.8.0 adds nothing on the client.
3. Start the server. Settings are in `config/mejta/` (MejtaMarket's price list in `mejtamarket-prices.json`); existing settings are kept. MejtaCrates creates an example crate on first start.
4. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar and of `mejta-<tag>.zip`, which holds all of them.

## MejtaCore 1.8.0: shared economy

`MejtaCoreAPI.economy()` is a shared economy, like Vault: MejtaCore defines it and an economy addon (MejtaEconomy) keeps the accounts. Shops, crates and auction houses use it without depending on the economy addon. Amounts are exact (whole minor units), transfers are all-or-nothing, deposits work for offline players, and every change names the addon and reason for the transaction log. Without an economy addon nothing changes and callers are told there is no economy.

## MejtaEconomy 1.0.0

- Balances with a configurable currency (symbol, names, 0 to 4 decimals, starting and maximum balance).
- `/balance` (`/bal`), `/pay` (also to offline players), `/baltop` with the money in circulation, and `/eco history`.
- Staff: `/eco give|take|set|reset|status`, audited. Players are told on joining what they received while offline.
- Accounts are saved atomically; every change is in a transaction log with the addon and reason behind it.

## MejtaMarket 1.0.0

- **Auction house** (`/ah`, `/market`): `/ah sell <price> [amount]`, then browse, search, filter and sort in a menu (a chest, or a screen with MejtaCore on the client); buy with a confirmation. Listings expire after 48 hours.
- **Buy orders:** `/ah order <amount> <price each> [item]` sets the money aside; others sell into it from the menu.
- **Collection box** for bought, returned and ordered items and payments that could not be made at once.
- **Fees:** a 1 % listing fee and a 5 % sales tax, both taken out of the economy.
- **Market bot** ("Market Trader"): buys cheap listings, sells into generous buy orders and keeps a few listings and orders of its own, with prices from real player trades. It has a daily budget and limited stock, cannot be farmed, takes more money out than it adds, and fades out as more players come online.
- **Duplication-safe:** every listing, fill and payment is written down before items or money move, and finished at the next join after a crash. Every trade is logged.

## MejtaCrates 1.0.0

- **Crates** with weighted rewards of items, console commands and money, edited in game with `/crates admin`.
- **Keys:** virtual keys (a count per player) and physical key items. `/crates give|giveall|take` for staff and the console, so donation stores can sell keys, also to offline players and names that have never joined.
- **Opening:** a spinning animation with sounds and a skip button (a MejtaCore screen, or a chest for vanilla clients), a preview with each reward's chance, and crate blocks in the world.
- **Duplication-safe:** the key is taken and the prize recorded before the animation; the prize is handed out once, also after a disconnect or a crash. Physical keys carry a batch serial, so copies are caught and taken away. Everything is audited.

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse, MejtaSMP and now MejtaEconomy, MejtaMarket and MejtaCrates. MejtaMarket's lists, buys, fills a buy order, refuses a listing whose save fails and lets the bot buy, checking every item and coin after each step.
- **Client game tests** only for new features, in their pull requests.

## Known limitations

See each mod's README.
