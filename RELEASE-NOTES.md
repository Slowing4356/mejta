# Mejta v2026.10.7

All eleven Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **Updated:** MejtaCore 1.9.0 (server menus are chests by default, with one shared look), MejtaMarket 1.1.0 (sign search, a sell button, a bot that follows demand), and MejtaQoL 1.2.0, MejtaSMP 1.1.0, MejtaUtils 1.1.0 and MejtaCrates 1.1.0 (their menus in the shared look, with pages).
- **Unchanged since v2026.10.6:** MejtaAC, MejtaDiscord, MejtaPerf, MejtaCorpse and MejtaEconomy.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.9.0 (updated) | Shared platform: settings with in-game editing (a settings editor with MejtaCore on the client, a chest menu for vanilla clients), permissions, notifications, secrets, data store, audit log, server menus (chests with one shared look for everyone, screens on request for players with MejtaCore), scheduler, metrics, shared AFK and combat status, and the shared economy API. Required by every other mod. | Server (client optional, for the `/mejta` menu, the settings editor and menu screens) |
| **MejtaAC** | 1.1.1 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.1.0 (updated) | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.2.0 | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour, with backups of the last deaths that staff can restore. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.2.0 (updated) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.1.0 (updated) | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |
| **MejtaEconomy** | 1.0.0 | Player balances, `/pay`, `/baltop`, staff commands and a transaction log. Provides MejtaCore's economy, which the market and crates use. | Server |
| **MejtaMarket** | 1.1.0 (updated) | Auction house (`/ah`) with buy orders, a collection box, sign search, fees and a market bot that follows demand and keeps trading going on quiet servers. Built so items can never be duplicated. | Server |
| **MejtaCrates** | 1.1.0 (updated) | Crates opened with keys: weighted rewards of items, commands and money, a spinning opening animation and a preview. Staff and the console (donation stores) give keys, also to offline players. Built so keys and rewards can never be duplicated. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaQoL, MejtaSMP, MejtaUtils and MejtaCrates now need MejtaCore **1.9.0** or newer; MejtaMarket and MejtaEconomy 1.8.0 or newer; the others 1.3.0 or newer, all below 2.0.0. MejtaCore 1.9.0 keeps working with addons built for the previous APIs. Addons never depend on each other directly, but **MejtaMarket needs an economy addon** (MejtaEconomy): without one the market stays closed. MejtaCrates works without one and leaves out money rewards. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.9.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars, or take them all from `mejta-v2026.10.7.zip`. Coming from v2026.10.6, replace MejtaCore, MejtaMarket, MejtaQoL, MejtaSMP, MejtaUtils and MejtaCrates. **Update MejtaCore together with them**: the new MejtaQoL, MejtaSMP, MejtaUtils and MejtaCrates do not load on MejtaCore 1.8.
2. Players with MejtaCore on their client: update it to 1.9.0 to get chest menus by default. A 1.7 or 1.8 client still asks the server for screens (its old default), so those players keep seeing screens until they update or switch **Server Menus** off on the `/mejta` overview.
3. Start the server. Settings are in `config/mejta/`; existing settings are kept.
4. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar and of `mejta-v2026.10.7.zip`, which holds all of them.

## MejtaCore 1.9.0: chest menus for everyone

- **Server menus are chests by default.** Homes, warps, the auction house, crates and staff panels look the same for every player. Players with MejtaCore 1.9 on their client can still choose MejtaCore screens: the setting is now `menus_as_screens` (off by default), switched by the Server Menus card on the `/mejta` overview. The old `server_menu_screens` key is no longer read.
- **One look for every chest menu:** the same bottom row everywhere (Back, Previous page, an info icon with the page number, Next page, Close), pages that turn in place, and gray panes in the empty slots. `/mejtaadmin config menu` uses it too. For addon authors: `PagedMenu`, `ServerMenu.Builder.backButton`, `closeButton` and `fillEmpty`, and `MenuIcons` (API version 7).

## Addon menus in the shared look

- **MejtaQoL 1.2.0:** homes and warps menus have pages, so every home and warp is listed (it stopped at 45). Deleting a home brings you back to the page you were on, and the icons are clearer (yellow to teleport, red to delete).
- **MejtaUtils 1.1.0:** `/invsee claims` has pages, so every claim is listed (it stopped at 53). `/vanish` settings and `/status` get the shared look; the game mode picker gets a Back button and says which mode is current.
- **MejtaSMP 1.1.0:** sidebar and leaderboard menus get the shared look.
- **MejtaCrates 1.1.0:** the crate list and the reward preview get the shared bar, with pages in the preview.

## MejtaMarket 1.1.0

- **Search on a sign:** the search button (and `/ah search` without text) opens a sign to type on, also on vanilla clients. Right click the button to clear the search. An empty result says when the bot will bring the item.
- **Sell from the menu:** the help button is now a sell button showing what you hold and its market price; click it (or use `/ah sell` without a price) to type a price on a sign.
- **The bot follows demand:** searches are remembered per item (fading over 6 hours, one count per player every 10 minutes). The bot lists wanted items more often, lists an item someone searched for that nobody sells within a minute and tells them, and withdraws its least wanted listing first when at its limit. Budget, stock and price rules are unchanged.

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse, MejtaSMP, MejtaEconomy, MejtaMarket and MejtaCrates, now including MejtaMarket's sign search and demand-driven bot.
- **Client game tests** only for new features, in their pull requests.

## Known limitations

See each mod's README.
