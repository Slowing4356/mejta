# Mejta v2026.10.1

All five Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.4.0 | Shared platform: settings with in-game editing, permissions, notifications, secrets, data store, audit log, server menus, scheduler and metrics. Required by every other mod. | Server (client optional, for the `/mejta` menu) |
| **MejtaAC** | 1.1.0 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.0 (new) | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 (new) | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |

Every addon needs **MejtaCore 1.4.0** (1.3.0 still loads, but has the menu bugs below) and **Fabric API**. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.4.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept.
3. See each mod's README for commands, permissions and settings. In particular:
   - **MejtaAC:** review alerts in `DRY_RUN` before enforcing. The new mitigation is off by default.
   - **MejtaDiscord:** set webhook URLs and the bot token from the **server console**, never from in-game chat.
4. If players also have MejtaCore on their client for the `/mejta` menu, they should update it too.

`SHA256SUMS.txt` lists the checksum of every jar.

## What's new

**MejtaCore 1.4.0**
- **Menu edits now reach the server.** With Mejta mods also installed on the client, the Configuration page edited the client's own files. Configs the server offers now open the server's copy; Shift-click opens the local one, labelled "(this computer)".
- **Refusals are shown** under the menu and in chat, quick clicks are no longer undone, Reset All resets every setting, and a failed save is reported instead of announced as a change (also for commands).
- **New `/mejtaadmin config menu`:** a chest menu for editing server settings from a vanilla client, with the same permission check, validation, audit log and notifications as the commands. Secrets are never shown or editable there.

**MejtaAC 1.1.0**
- **Mitigation (experimental, off by default):** `enforcement.mitigation` undoes the action behind a flag: set the player back, cancel the hit, or cancel the block break. Kicks are unchanged.
- **New checks (experimental, MEDIUM at most):** `movement.elytra` (elytra flight that ignores glide physics) and `vehicle.horse` (horses, donkeys, mules and camels moving or jumping beyond their own attributes).
- **Fix:** `movement.speed` no longer flags landings from a legitimate elytra glide.

**MejtaUtils 1.0.0 and MejtaPerf 1.0.0:** first release. MejtaPerf includes `/mperf profiler slow <ms>`, which profiles only ticks slower than the given time.

**MejtaDiscord 1.0.0:** unchanged.

## Tested

The release workflow builds every jar from the tagged commit and runs the unit tests. Before tagging, the client game tests were run for every mod (a real Minecraft client joins a real dedicated server and drives commands, menus and clicks). For MejtaAC, legitimate play under five latency profiles (0 to 500 ms, jitter and freezes) must cause zero flags, and every simulated cheat must be detected.

Not covered by automated tests: delivery to real Discord, and real hack clients. Each README lists what to check by hand.

## Known limitations

See each mod's README.
