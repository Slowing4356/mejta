# Mejta v2026.10.5

All eight Mejta mods for **Minecraft Java 26.1.2** (Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25).
- **Updated:** MejtaCore 1.7.0 (a settings editor screen, addon menus as screens, a new `/mejta` menu) and MejtaCorpse 1.2.0 (death backups that staff can restore).
- **Unchanged since v2026.10.4:** MejtaAC, MejtaUtils, MejtaDiscord, MejtaPerf, MejtaQoL and MejtaSMP. Their menus still open as screens for players with MejtaCore 1.7 on their client: that comes from MejtaCore alone.

| Mod | Version | What it does | Install on |
|---|---|---|---|
| **MejtaCore** | 1.7.0 (updated) | Shared platform: settings with in-game editing (a settings editor with MejtaCore on the client, a chest menu for vanilla clients), permissions, notifications, secrets, data store, audit log, server menus (screens for players with MejtaCore, chests for everyone else), scheduler, metrics, and shared AFK and combat status. Required by every other mod. | Server (client optional, for the `/mejta` menu, the settings editor and menu screens) |
| **MejtaAC** | 1.1.1 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`: it alerts and records, but never kicks until you switch it to `ENFORCE`. | Server |
| **MejtaUtils** | 1.0.2 | Staff tools: `/invsee` and `/ecsee` (also offline players), `/status`, `/vanish`, `/freeze`, `/staffmode`. | Server |
| **MejtaDiscord** | 1.0.0 | Anti-cheat alerts and Minecraft chat to Discord webhooks; (experimental) Discord chat to Minecraft. | Server |
| **MejtaPerf** | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and saved reports (`/mperf`). | Server |
| **MejtaCorpse** | 1.2.0 (updated) | Corpses that keep a dead player's items in a chest menu, wearing their skin and armour, with backups of the last deaths that staff can restore. Built so items can never be duplicated. | Server |
| **MejtaQoL** | 1.1.1 | Player commands: `/tpa`, homes, warps, `/spawn`, `/back`, `/rtp`, `/ec`, portable workstations, `/trash`, AFK and `/r`. | Server |
| **MejtaSMP** | 1.0.1 | SMP features: AFK protection, sidebar and leaderboards, item and XP clumping with piles bigger than a stack, infinite trades, one-player sleep, Nether/End toggles, locator bar off and an account limit. | Server |

Every addon needs **MejtaCore** and **Fabric API**. MejtaQoL and MejtaSMP need MejtaCore **1.5.0** or newer; the others accept 1.3.0 or newer, below 2.0.0. MejtaCore 1.7.0 keeps the same public API (version 5), so every addon works with it. Addons never depend on each other: install only what you want. Vanilla clients can join.

## Install or update

1. Put `mejtacore-1.7.0.jar`, Fabric API and the addons you want in the server's `mods/` folder, replacing older Mejta jars. Coming from v2026.10.4, only the MejtaCore and MejtaCorpse jars changed.
2. Optionally, players and staff put `mejtacore-1.7.0.jar` (with Fabric API) in their client's `mods/` folder for the `/mejta` menu, the settings editor and menu screens. Clients without it keep the chest menus.
3. Start the server. Settings are in `config/mejta/`; existing settings are kept. MejtaCore's old `menu_rows` setting is no longer used (menus scroll now).
4. See each mod's README for commands, permissions and settings.

`SHA256SUMS.txt` lists the checksum of every jar.

## MejtaCore 1.6.0 and 1.7.0

- **Settings editor.** With MejtaCore on the client, `/mejta config` and `/mejtaadmin config` open one screen with every config in a sidebar (the server's and this computer's, kept apart) and each setting with a widget that fits it: ON/OFF, options, a slider plus a field for numbers, text. Values are checked as you type; there is a reset per setting, a marker on changed settings, search, a filter for changed settings, **Undo Changes**, **Reset All** and **Reload Files**. `/mejta config <config>` opens one config directly. `/mejtaadmin config menu` is still the chest menu, for vanilla clients.
- **Addon menus as screens.** Every server menu (MejtaQoL homes and warps, MejtaSMP sidebar and leaderboards, MejtaUtils vanish and status panels, confirmations) opens as a MejtaCore screen for players with MejtaCore 1.7 on their client: a card per icon, in the chest's order. The server still runs everything. Menus that hold real items (`/invsee`, `/ec`, corpses, `/trash`, workstations) stay inventories. Players can switch back to chests with **Server Menus** on the `/mejta` overview (client setting `server_menu_screens`).
- **New `/mejta` menu** in the settings editor's style: **Overview**, a page per addon (version, status, dependencies, settings, actions), **Problems** and **About**.

## MejtaCorpse 1.2.0: death backups

- The items of each player's last 10 deaths are kept (`backup.per_player`, 0 to 100; `backup.keep_days` deletes older ones), with what then happened to the corpse: who took how many items, or whether it expired.
- Staff with `mejtacorpse.restore` (admins by default) can give a death's items back once its corpse is gone: `/corpse backups <player>`, then **[Restore]** and **[Confirm restore]** (or `/corpse restore <player> <id> [confirm]`). Restoring your own death also needs `mejtacorpse.restore.self` (owners by default).
- Built against duplication: only once the corpse is gone, each backup only once (marked on disk before anything is given), and only after a confirmation that warns when the owner already took items back. Every restore is audited (`corpse.restore`), logged and announced to staff, and the player is told (`backup.notify_owner`).

## Tested

- **Release workflow:** builds every jar from the tagged commit and runs the unit tests.
- **Every CI build:** the headless server game tests of MejtaQoL, MejtaCorpse and MejtaSMP. MejtaCorpse's now cover backups: a death is backed up, cannot be restored while its corpse exists, asks to confirm, puts the items back in their slots and is restored only once.
- **Client game tests** of every project (manual CI run on `main` before tagging), including the new settings editor, the `/mejta` menu and menu screens in MejtaCore's test addon. MejtaAC's normal run now covers only its newest checks (stage 6) in the worst-case latency profile; unit tests still cover every check.

## Known limitations

See each mod's README.
