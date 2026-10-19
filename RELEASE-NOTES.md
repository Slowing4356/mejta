# Mejta v2026.10.2

All seven Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25). New in this release: **MejtaQoL** and **MejtaCorpse**. The other mods are unchanged since v2026.10.1.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.4.0 | Shared platform: settings with in-game editing, permissions, notifications, secrets, data store, audit log, server menus, scheduler and metrics. Required by every other mod. | Server (client optional, for the `/mejta` menu) |
| **MejtaAC** | 1.1.0 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.0 | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.0.0 (new) | Corpses that keep a dead player's items in a chest menu, wearing their skin. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.0.0 (new) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |

Every addon needs **MejtaCore** (1.4.0 recommended; 1.3.0 or newer, below 2.0.0, is accepted) and **Fabric API**. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.4.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept.
3. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar.

## New: MejtaQoL 1.0.0

- **Teleports:** `/tpa` and `/tpahere` with clickable Accept and Deny buttons, `/tptoggle`, `/back` (also to your death point), `/spawn` and `/setspawn`. Every teleport has a short wait that moving or damage cancels, a combat lock after PvP, and a cooldown per feature.
- **Homes and warps:** `/sethome`, `/home`, `/delhome`, `/homes` (chest menu), `/warp`, `/warps` (chest menu), `/setwarp`, `/delwarp`. Staff can manage other players' homes, also offline.
- **Random teleport (experimental):** `/rtp`, with a safe-spot search inside the world border. Each try loads a chunk on the server thread; see the README.
- **Anywhere:** `/ec` (your ender chest), `/craft`, `/anvil`, `/stonecutter`, `/grindstone`, `/smithing`, `/loom`, `/cartography` and `/trash`.
- **AFK and chat:** `/afk`, automatic AFK with an `[AFK]` tag and an optional idle kick, `/r` for private messages, a join greeting and death coordinates.
- **Customisable:** every feature has its own on/off setting (switched-off commands disappear from players' command lists) and its own permission node, and limits, cooldowns, waits and messages are settings. Player commands are open to everyone by default; raise a node's level to make it staff-only.
- `/qol` shows each player the commands they can use.

## New: MejtaCorpse 1.0.0

- **Corpses:** when a player dies, their items go into a corpse where they died: a vanilla mannequin lying down in their skin, named "<player>'s corpse". Players need no mod.
- **Menu:** right-clicking opens a chest menu laid out like the inventory screen. **Take all** gives the owner every item back in its old slot, armour included.
- **Access:** only the owner (and staff) by default. A setting can make corpses public after a delay, and an optional expiry drops or deletes the items.
- **No duplication:**
  - At death, the corpse is written to disk before the items leave the inventory, and the player is saved before the corpse can be opened.
  - Every item taken from a corpse is removed on disk before the player gets it.
  - A stop during a death is resolved at the owner's next join.
  - A failed disk write moves nothing.
- **Commands:** `/corpse` lists your corpses. Staff can list, open and teleport to anyone's.

## Tested

The release workflow builds every jar from the tagged commit and runs the unit tests. MejtaQoL's and MejtaCorpse's headless server game tests run in every CI build. MejtaCorpse's test checks, after every kind of click, after a failed disk write and after an interrupted death, that each item exists exactly once. The client game tests for the other mods were run before v2026.10.1, and those mods have not changed since.

Not covered by automated tests: MejtaQoL's chest menus, the timed teleport wait, the AFK broadcast and kick, and `/rtp` end to end; right-clicking a corpse with a real client and its look (each README lists them); delivery to real Discord; and real hack clients.

## Known limitations

See each mod's README.
