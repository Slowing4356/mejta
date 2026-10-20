# Mejta v2026.10.3

All eight Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **New:** MejtaSMP.
- **Updated:** MejtaCore 1.5.0, MejtaQoL 1.1.0 and MejtaCorpse 1.1.0.
- **Performance updates:** MejtaAC 1.1.1 and MejtaUtils 1.0.1.
- **Unchanged since v2026.10.2:** MejtaDiscord and MejtaPerf.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.5.0 (updated) | Shared platform: settings with in-game editing, permissions, notifications, secrets, data store, audit log, server menus, scheduler, metrics, and shared AFK and combat status. Required by every other mod. | Server (client optional, for the `/mejta` menu) |
| **MejtaAC** | 1.1.1 (faster) | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.1 (faster) | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.1.0 (updated) | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.1.0 (updated) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.0.0 (new) | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaQoL 1.1.0 and MejtaSMP need MejtaCore **1.5.0** or newer; the others accept 1.3.0 or newer, below 2.0.0. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.5.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept.
3. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar.

## New: MejtaSMP 1.0.0

- **AFK protection:** AFK players cannot be hurt by other players, unless they are in combat. Optionally, mobs cannot hurt them either.
- **Scoreboards:**
  - a sidebar each player picks with `/sb`: their kills, deaths, K/D, kill streak, mob kills and play time, or the top kills or top play time;
  - `/leaderboard` with the top 21 players and your own rank;
  - an optional score in the player list.
- **Items and XP:**
  - XP orbs clump and are taken at once;
  - dropped items merge from farther away;
  - **(experimental)** dropped items form piles bigger than a stack, such as "Cobblestone ×1,000" as one item, for less lag. Players, hoppers and mobs take a stack at a time, and items are never duplicated.
- **World:** infinite villager trades, one-player sleep, closable Nether and End portals, and the locator bar hidden.
- **Account limit:** at most 2 accounts online at once per internet address by default, and optionally a cap on different accounts. Addresses are only kept as salted hashes, never shown or logged.

## MejtaCore 1.5.0

- **Shared AFK and combat status** for every addon (API version 5). AFK detection moved here from MejtaQoL. A PvP hit puts both players in combat for `combat.tag_seconds` (10).
- **Grouped settings menus:** a config with more than 18 settings opens on groups. For example, MejtaQoL shows Teleport, Tpa, Homes and so on instead of 49 settings at once. This applies to `/mejtaadmin config menu` and to the client menu.

## MejtaQoL 1.1.0

- **AFK and combat from MejtaCore:** AFK and the combat lock now use MejtaCore's status, and `/afk` is refused in combat.
  - `teleport.combat_seconds` and `afk.auto_minutes` moved to `mejtacore.json` as `combat.tag_seconds` and `afk.auto_minutes`, with the same defaults. If you had changed them, set them again there.
- **Cooldowns:** `/tpa` and `/home` default to a 3-second cooldown (before: 10 and 5). Existing settings files keep their values. To use the new ones:

  ```
  /mejtaadmin config set mejtaqol tpa.cooldown_seconds 3
  /mejtaadmin config set mejtaqol homes.cooldown_seconds 3
  ```

## Performance

Every mod was reviewed for server-thread cost with 50 players in mind, and all are lightweight. These were improved:
- **MejtaCorpse:** a death saved every online player (about 150 file writes with 50 online); now only the dying player is saved.
- **MejtaUtils:** a joining player's vanish state was read from disk up to about 100 times; now it is read once.
- **MejtaAC:**
  - entity positions for reach and hitbox are recorded only while those checks are on, and only for entities that can be attacked;
  - the piston check usually reads no blocks at all.
- **MejtaQoL:** `/rtp` skips ocean columns without loading chunks, and its cooldown starts with the search.
- **MejtaCore:** notification mutes are read only for players allowed to see the notification, and forgotten when they leave.

## MejtaCorpse 1.1.0

- **Visible armour:** a corpse wears the armour it holds. It is only a display copy:
  - killing the body drops nothing, and dispensers cannot equip it;
  - a piece taken from the menu also leaves the body.
- **New setting:** `display.show_armor` (default on).

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse and MejtaSMP. They check, among other things:
  - MejtaQoL: the combat lock;
  - MejtaCorpse: the armour copy;
  - MejtaSMP: that piles hold every item exactly once through merging, pickup, hoppers, saving, lava and despawning.
- **Client game tests** (a real client on a dedicated server) ran for MejtaCorpse and MejtaSMP. MejtaSMP's checks the sidebar, a labelled pile of 300 picked up exactly once, XP clumping and its menus.
- **Other mods:** their client game tests were run before v2026.10.1, and they have not changed since.

**Not covered by automated tests:**
- MejtaQoL's chest menus, the timed teleport wait, the AFK broadcast and kick, and `/rtp` end to end;
- the grouped settings menus with a real client;
- a corpse wearing a player's real skin (tested only with an offline player);
- the account limit with real connections from one address;
- delivery to real Discord;
- real hack clients.

## Known limitations

See each mod's README.
