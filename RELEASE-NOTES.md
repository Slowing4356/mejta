# Mejta v2026.10.3

All seven Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25). Changed in this release: **MejtaCorpse 1.1.0** and **MejtaQoL 1.0.1**. The other mods are unchanged since v2026.10.2.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.4.0 | Shared platform: settings with in-game editing, permissions, notifications, secrets, data store, audit log, server menus, scheduler and metrics. Required by every other mod. | Server (client optional, for the `/mejta` menu) |
| **MejtaAC** | 1.1.0 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.0 | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.1.0 (updated) | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.0.1 (updated) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |

Every addon needs **MejtaCore** (1.4.0 recommended; 1.3.0 or newer, below 2.0.0, is accepted) and **Fabric API**. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.4.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept.
3. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar.

## MejtaCorpse 1.1.0

- **Visible armour:** a corpse now wears the armour it holds, so you can see it from afar.
- **Still duplication-safe:** the armour on the body is only a display copy, and the items stay in the corpse:
  - Killing the body drops nothing.
  - Clicking the body opens the menu, as before.
  - Dispensers cannot put armour on a corpse.
  - A piece taken from the menu also leaves the body.
- **New setting:** `display.show_armor` (default on).

## MejtaQoL 1.0.1

- **Cooldowns:** `/tpa` and `/home` now share the same default cooldown of **3 seconds** (before: 10 and 5).
- **Unchanged:** both already used the same 10-second PvP combat lock (`teleport.combat_seconds`) and the same 3-second wait that moving or damage cancels. All of these are settings.
- **Existing servers:** a settings file that already exists keeps its values. To get the new defaults, change them once:

  ```
  /mejtaadmin config set mejtaqol tpa.cooldown_seconds 3
  /mejtaadmin config set mejtaqol homes.cooldown_seconds 3
  ```

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** MejtaQoL's and MejtaCorpse's headless server game tests. MejtaCorpse's test now also checks:
  - the body wears a copy of the armour;
  - killing the body drops nothing, and a dispenser cannot equip it;
  - a taken piece leaves the body.
- **MejtaCorpse's new client game test:** a real client on a dedicated server, with a real player:
  - dies and sees the corpse wearing their armour;
  - right-clicks the corpse to open it;
  - takes items with real clicks and uses Take all;
  - checks every item comes back exactly once.

  It ran locally and on GitHub.
- **Other mods:** their client game tests were run before v2026.10.1, and they have not changed since.

**Not covered by automated tests:**
- MejtaQoL's chest menus, the timed teleport wait, the AFK broadcast and kick, and `/rtp` end to end;
- a corpse wearing a player's real skin (tested only with an offline player);
- delivery to real Discord;
- real hack clients.

## Known limitations

See each mod's README.
