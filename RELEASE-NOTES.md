# Mejta v2026.10.8

All eleven Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **Updated:** MejtaCore 1.10.0 (player ranks; the cursor stays put between chest menus), MejtaQoL 1.3.0 and MejtaMarket 1.2.0 (rank perks; signs that stay open and a busier market bot), MejtaEconomy 1.1.0 (a staff balance menu), and MejtaUtils 1.1.1 and MejtaCrates 1.1.1 (the cursor stays put).
- **Unchanged since v2026.10.7:** MejtaAC, MejtaDiscord, MejtaPerf, MejtaCorpse and MejtaSMP.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.10.0 (updated) | Shared platform: settings with in-game editing (a settings editor with MejtaCore on the client, a chest menu for vanilla clients), permissions, notifications, secrets, data store, audit log, server menus (chests with one shared look for everyone, screens on request for players with MejtaCore), scheduler, metrics, shared AFK and combat status, the shared economy API, and player ranks (Member, Bronze, Silver, Gold). Required by every other mod. | Server (client optional, for the `/mejta` menu, the settings editor and menu screens) |
| **MejtaAC** | 1.1.1 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.1.1 (updated) | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.2.0 | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour, with backups of the last deaths that staff can restore. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.3.0 (updated) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.1.0 | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |
| **MejtaEconomy** | 1.1.0 (updated) | Player balances, `/pay`, `/baltop`, staff commands, a staff balance menu (`/eco menu`) and a transaction log. Provides MejtaCore's economy, which the market and crates use. | Server |
| **MejtaMarket** | 1.2.0 (updated) | Auction house (`/ah`) with buy orders, a collection box, sign search, fees and a market bot that follows demand and keeps trading going on quiet servers. Built so items can never be duplicated. | Server |
| **MejtaCrates** | 1.1.1 (updated) | Crates opened with keys: weighted rewards of items, commands and money, a spinning opening animation and a preview. Staff and the console (donation stores) give keys, also to offline players. Built so keys and rewards can never be duplicated. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaQoL and MejtaMarket now need MejtaCore **1.10.0** or newer; MejtaSMP, MejtaUtils and MejtaCrates 1.9.0 or newer; MejtaEconomy 1.8.0 or newer; the others 1.3.0 or newer, all below 2.0.0. MejtaCore 1.10.0 keeps working with addons built for the previous APIs. Addons never depend on each other directly, but **MejtaMarket needs an economy addon** (MejtaEconomy): without one the market stays closed. MejtaCrates works without one and leaves out money rewards. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.10.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars, or take them all from `mejta-v2026.10.8.zip`. Coming from v2026.10.7, replace MejtaCore, MejtaQoL, MejtaMarket, MejtaEconomy, MejtaUtils and MejtaCrates. **Update MejtaCore together with them**: the new MejtaQoL and MejtaMarket do not load on MejtaCore 1.9.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept, and the new rank settings are added with their defaults. Every player starts as a Member; give ranks with `/mejtaadmin rank set <player> <rank>`.
3. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar and of `mejta-v2026.10.8.zip`, which holds all of them.

## MejtaCore 1.10.0: ranks

- **Four ranks:** every player is a Member; staff, the console or a donation store give Bronze, Silver or Gold with `/mejtaadmin rank set <player> <rank>` (also `list`, `get` and `clear`; permission `mejtacore.ranks.manage`). Works for offline players and is audited.
- **Bronze by playtime:** a Member who has played `ranks.playtime_hours` (default 50, `0` = off) becomes Bronze.
- **Prefixes** (`ranks.<rank>.prefix`, with `&` colour codes) before names in chat, the tab list and above heads (`ranks.show_prefix`, on by default).
- **Permissions by rank:** `config/mejta/mejtacore.rankperms.json` sets the lowest rank that gets each addon permission (`OFF`, `BRONZE`, `SILVER`, `GOLD`). MejtaCore's own permissions are never given by rank.
- For addon authors: `MejtaCoreAPI.ranks()`, `RankService.byRank(...)`, `RankListener` and `MejtaPermission#grantedFrom(Rank)` (API version 8).

## Rank perks

| Mod | Perk | Member | Bronze | Silver | Gold |
|---|---|---|---|---|---|
| MejtaQoL 1.3.0 | Homes (`homes.max`, `homes.max_bronze`, `_silver`, `_gold`) | 3 | 5 | 6 | 7 |
| MejtaQoL 1.3.0 | `/rtp` cooldown (`rtp.cooldown_seconds`, `_bronze`, `_silver`, `_gold`) | 5 min | 4 min | 3 min | 2 min |
| MejtaMarket 1.2.0 | Listings on sale at once (`listing.max_per_player`, `_bronze`, `_silver`, `_gold`) | 10 | 13 | 16 | 20 |

Fees, prices and the market bot are the same for every rank. MejtaQoL's `homes.extra` permission still gives `homes.max_extra` when that is more.

## The cursor stays put

Going from one chest menu to the next (a page, a confirmation, Back, a sub-menu) used to close the chest and open a new one, which put the mouse back in the middle of the screen. The new chest now replaces the old one directly (MejtaCore 1.10.0), and confirmations, opening a crate (MejtaCrates 1.1.1) and taking a claim in `/invsee claims` (MejtaUtils 1.1.1) no longer close the menu first.

## MejtaMarket: signs and the bot

- **Search and price signs stay open:** the sign used to close as soon as it opened. It now stays open until you are done.
- **The bot is visibly active:** it keeps at least 6 listings on sale (`bot.min_listings`), listing about every 30 seconds while it has fewer, and acts every 2 to 6 minutes instead of 4 to 12 (new installs; existing settings files keep their values). Each bot action is written to the server log.

## MejtaEconomy 1.1.0: balance menu

`/eco menu` lists every account (online players first, with their balance). Click one to give or take 1, 10, 100, 1,000 or 10,000 with one click, set an exact amount, reset to 0 (shift-click) or see the history. `/eco menu <player>` opens a player directly, also one who never joined. Needs `mejtaeconomy.admin`; every change is audited and logged like `/eco give|take|set`.

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse, MejtaSMP, MejtaEconomy, MejtaMarket and MejtaCrates, now including MejtaMarket's sign round trip.
- **Client game tests** only for new features, in their pull requests.

## Known limitations

See each mod's README.
