# Mejta v2026.10.4

All eight Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **Updated:** MejtaQoL 1.1.1, MejtaSMP 1.0.1 and MejtaUtils 1.0.2: a switch for operator bypasses.
- **Unchanged since v2026.10.3:** MejtaCore, MejtaAC, MejtaDiscord, MejtaPerf and MejtaCorpse.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.5.0 | Shared platform: settings with in-game editing, permissions, notifications, secrets, data store, audit log, server menus, scheduler, metrics, and shared AFK and combat status. Required by every other mod. | Server (client optional, for the `/mejta` menu) |
| **MejtaAC** | 1.1.1 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.2 (updated) | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.1.0 | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.1.1 (updated) | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.0.1 (updated) | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaQoL and MejtaSMP need MejtaCore **1.5.0** or newer; the others accept 1.3.0 or newer, below 2.0.0. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.5.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars. Coming from v2026.10.3, only the MejtaQoL, MejtaSMP and MejtaUtils jars changed.
2. Start the server. Settings are in `config/mejta/`; existing settings are kept.
3. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar.

## Operator bypass switch

Operators were skipping rules without anyone noticing: for example, teleports were instant and ignored the combat lock and the cooldowns, because operators have `mejtaqol.teleport.bypass`. Every mod with bypass permissions now has a setting for them:

| Mod | Setting | Default | Bypass permissions it switches |
|---|---|---|---|
| MejtaQoL 1.1.1 | `op_bypass.enabled` | off | `mejtaqol.teleport.bypass` (teleport wait, cooldowns, combat lock, dimension rule), `mejtaqol.afk.kick_exempt` |
| MejtaSMP 1.0.1 | `op_bypass.enabled` | off | `mejtasmp.dimensions.bypass` (closed Nether or End), `mejtasmp.antialt.bypass` (account limit) |
| MejtaUtils 1.0.2 | `op_bypass.enabled` | off | `mejtautils.freeze.exempt` |
| MejtaAC 1.1.1 (unchanged) | `exemptions.bypass_permission` | on | `mejtaac.bypass` (all checks; owners only) |

With a switch off, operators follow the rules like everyone else. In MejtaUtils, players with `mejtautils.protected` (owners by default) can still only be frozen by staff who have it too. MejtaCore, MejtaCorpse, MejtaDiscord and MejtaPerf have no bypass permissions.

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse and MejtaSMP. They now also check that an operator is refused a teleport in combat (MejtaQoL) and a closed Nether (MejtaSMP), and is let through only with `op_bypass.enabled` on.
- **Client game test** of MejtaUtils: 78 passed.

## Known limitations

See each mod's README.
