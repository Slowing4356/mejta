# Mejta: first release

The first release of the Mejta mods for **Minecraft Java 26.1.2**:
- Fabric Loader 0.19.5 or newer;
- Fabric API 0.155.3+26.1.2;
- Java 25.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.3.0 | The shared platform: settings (with in-game editing), permissions, notifications, secrets, data store, audit log, presence, server menus, scheduler and metrics. Required by every other mod. | Server (and client, for the `/mejta` menu) |
| **MejtaAC** | 1.0.0 | Server-side anti-cheat with lag-compensated movement, combat and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.0 | Staff tools: `/invsee` and `/ecsee` (online and offline), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts to Discord webhooks, Minecraft chat to Discord, and (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | `/mperf`: TPS and MSPT, lag alerts, a profiler that charges server time to mods, and reports. | Server |

Every addon needs **MejtaCore 1.3.0** and **Fabric API**. Addons never depend on each other: install only what you want. Vanilla clients can join; players need no mods.

## Install

1. Put `mejtacore-1.3.0.jar`, Fabric API and the addons you want in the server's `mods/` folder.
2. Start the server once. Settings appear in `config/mejta/`.
3. Read each mod's README for its commands, permissions and settings. In particular:
   - **MejtaAC:** review alerts in `DRY_RUN` before enforcing.
   - **MejtaDiscord:** set webhook URLs and the bot token from the **server console**, never from in-game chat.

`SHA256SUMS.txt` lists the checksum of every jar.

## Tested

Every jar in this release was built from the tagged commit by the release workflow, which also runs the unit tests. The client game tests run on demand (Actions > CI > Run workflow) and were run before tagging. CI covers:
- **Build:** every project compiled against Minecraft 26.1.2.
- **Unit tests:** JUnit 6.1.3 tests for the logic that needs no running game.
- **Client game tests:** a real Minecraft client joins a real dedicated server, under a virtual display. Each mod's test drives commands, menus and clicks and checks the results:
  - MejtaCore: about 190 checks.
  - MejtaAC: legitimate play under five latency profiles must produce zero flags, and every simulated cheat must be detected.
  - MejtaUtils, MejtaDiscord and MejtaPerf: their own end-to-end checks.

Not covered by automated tests:
- **Discord:** delivery to real Discord was not tested. Each mod's README has a short hand-check list.
- **Anti-cheat:** behaviour with real hack clients was not tested.

## Changes

See each mod's `CHANGELOG.md` for the full list. Before this release, every mod went through a full code review, and everything it found was fixed. The most important fixes:
- **MejtaAC:** a server freeze by forcing far chunk loads, false kicks after teleports, and MEDIUM-confidence flags no longer count towards kicks.
- **MejtaUtils:** two item-duplication paths, and item loss after a crash.
- **MejtaDiscord:** fake chat lines sent from Discord.
- **MejtaCore:** audit-log and data-store races.
