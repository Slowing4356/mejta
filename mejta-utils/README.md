# MejtaUtils

MejtaUtils (mod ID `mejtautils`) is a set of server-side staff tools for **MejtaCore**:
- `/invsee` and `/ecsee`: look at (and optionally edit) a player's inventory or ender chest, online or offline;
- `/status`: a live panel with a player's health, food, XP, effects and game mode;
- `/vanish`: hide from players and the server list;
- `/freeze`: hold a player in place while staff talk to them;
- `/staffmode`: a safe staff kit that saves and restores your survival state.

Players need no client mod. Every menu is a plain chest, and all checks run on the server. Permissions, settings, the audit log, notifications and data storage all go through MejtaCore.

| | |
|---|---|
| Version | 1.0.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.4.0 (any version from 1.3.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None |

## Installing

1. Put `mejtacore-1.4.0.jar`, `mejtautils-1.0.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. The settings file `config/mejta/mejtautils.json` is created on first start.

## Commands

Each command is also available as `/mejtaadmin mejtautils <command>`, in case another mod already uses the name. You can turn off a top-level name with MejtaCore's `commands.disabled` setting in `mejtacore.json`.

| Command | Permission | What it does |
|---|---|---|
| `/invsee <player> [view\|edit]` | `invsee.view`; `edit` needs `invsee.edit` | Open the player's inventory. Online players are shown live. Your own inventory always opens view-only. |
| `/invsee <offline player> [view\|edit]` | `invsee.view` + `invsee.offline`; `edit` needs `invsee.offline_edit` and the `invsee.offline.edit` setting | View the saved inventory, or queue changes (experimental). See [Offline inventories](#offline-inventories). |
| `/invsee claims` | `invsee.view` + `invsee.offline` | Items waiting for you from offline changes. Refused in staff mode. |
| `/ecsee <player> [view\|edit]` | `ecsee.view`; `edit` needs `ecsee.edit` | Open the player's ender chest (online or offline). |
| `/status <player>` | `status.view` | Live status panel (online players only). Clearing effects needs `status.effects`; changing game mode needs `status.gamemode`. Both ask for confirmation. |
| `/vanish [on\|off]` | `vanish` | Toggle vanish. |
| `/vanish settings` | `vanish` | Your own vanish settings (pickup, mob targeting, night vision, flight speed, silent containers). |
| `/vanish fakequit`, `/vanish fakejoin` | `vanish.fake` + the `vanish.fake_presence.enabled` setting | Send a fake leave or join message. |
| `/freeze <player> [reason]` | `freeze` | Freeze a player. An offline player is frozen when they join. |
| `/unfreeze <player>` | `freeze` | Unfreeze a player, online or offline. |
| `/freeze list`, `/freeze info <player>` | `freeze` + `freeze.info` | Frozen players, and what each one tried to do while frozen. |
| `/staffmode [on\|off]` | `staffmode` | Toggle staff mode. |
| `/staffmode restore confirm [player]` | `staffmode`; another player needs `staffmode.restore` | Restore a saved survival state that was not restored automatically. |
| `/mejtaadmin mejtautils pending` | `invsee.offline` | List queued offline changes. |
| `/mejtaadmin mejtautils pending cancel <player>` | `invsee.offline_edit` | Cancel an open change. Items you added wait in your `/invsee claims`. |
| `/mejtaadmin mejtautils pending discard <player>` | `admin` | Delete a change record in any state, without moving items. Check the player by hand first. |
| `/mejtaadmin mejtautils sessions` | `staffmode.restore` | List unfinished staff-mode sessions. |

## Permissions

All nodes start with `mejtautils.`. Change their levels in `config/mejta/mejtacore.permissions.json`. Every action re-checks its permission when it happens, not only when the command is typed, and an open edit menu closes if the permission is removed.

| Node | Default level | Allows |
|---|---|---|
| `mejtautils.invsee.view` | 1 (moderators) | View online inventories |
| `mejtautils.invsee.edit` | 2 (gamemasters) | Edit online inventories |
| `mejtautils.invsee.offline` | 1 | View offline inventories, `/invsee claims`, `pending`, receive `mejtautils.invsee` alerts |
| `mejtautils.invsee.offline_edit` | 3 (admins) | Queue offline changes; `pending cancel` |
| `mejtautils.ecsee.view` | 1 | View ender chests |
| `mejtautils.ecsee.edit` | 2 | Edit online ender chests |
| `mejtautils.status.view` | 1 | Open the status panel |
| `mejtautils.status.effects` | 2 | Clear effects from the panel |
| `mejtautils.status.gamemode` | 2 | Change game mode from the panel |
| `mejtautils.vanish` | 1 | `/vanish` and `/vanish settings` |
| `mejtautils.vanish.see` | 1 | See vanished staff |
| `mejtautils.vanish.fake` | 3 | `/vanish fakequit` and `fakejoin` |
| `mejtautils.freeze` | 1 | Freeze and unfreeze |
| `mejtautils.freeze.info` | 1 | `/freeze list`, `/freeze info`, freeze alerts |
| `mejtautils.freeze.exempt` | 4 (owners) | Cannot be frozen |
| `mejtautils.staffmode` | 1 | `/staffmode` and its tools |
| `mejtautils.staffmode.teleport` | 1 | The random-player compass |
| `mejtautils.staffmode.restore` | 3 | Confirm a restore for another player; `sessions`; staff-mode alerts |
| `mejtautils.protected` | 4 | Can only be inspected, edited or frozen by others who also have this node |
| `mejtautils.admin` | 3 | `pending discard` |

## Settings

Stored in `config/mejta/mejtautils.json`. Change them in the file, in MejtaCore's settings menu, or with `/mejtaadmin config set mejtautils <key> <value>`.

| Key | Default | Meaning |
|---|---|---|
| `invsee.refresh_ticks` | `10` (1–200) | How often the info row and status panel refresh, in ticks |
| `invsee.offline.enabled` | `true` | Allow viewing offline players (read-only) |
| `invsee.offline.edit` | `false` | **Experimental.** Allow queueing changes for offline players. Read [Offline inventories](#offline-inventories) first. |
| `invsee.offline.backups` | `5` (1–20) | Inventory backups kept per player before a queued change is applied |
| `vanish.default.item_pickup` | `false` | Default for new staff: pick up items and XP while vanished |
| `vanish.default.mob_targeting` | `false` | Default for new staff: mobs may target you |
| `vanish.default.night_vision` | `true` | Default for new staff: night vision while vanished |
| `vanish.default.flight_speed` | `1.0` (1–4) | Default for new staff: flight speed multiplier |
| `vanish.default.silent_containers` | `true` | Default for new staff: open containers without animation or sound |
| `vanish.persist_across_relog` | `true` | Rejoin vanished, with no join message. When off, you still leave silently but rejoin visible. |
| `vanish.hide_from_server_list` | `true` | Leave vanished staff out of the server list count and names |
| `vanish.fake_presence.enabled` | `false` | Allow `/vanish fakequit` and `fakejoin` |
| `vanish.block_game_events` | `true` | **Experimental.** Sculk sensors, shriekers and the Warden ignore vanished staff |
| `vanish.filter_sounds` | `true` | **Experimental.** Hide sounds and block-break particles caused by vanished staff |
| `freeze.block_movement` | `true` | Frozen players cannot move (they can still look around) |
| `freeze.block_item_drop` | `true` | Frozen players cannot drop items |
| `freeze.block_block_break` | `true` | Frozen players cannot break blocks |
| `freeze.block_block_place` | `true` | Frozen players cannot place blocks or use items on blocks |
| `freeze.block_interact` | `true` | Frozen players cannot use items or interact with entities |
| `freeze.block_attack` | `true` | Frozen players cannot attack |
| `freeze.block_commands` | `true` | Frozen players can only use `freeze.allowed_commands` |
| `freeze.allowed_commands` | `msg,tell,w,r` | Commands still allowed while frozen. Chat is always allowed. |
| `freeze.invulnerable` | `false` | Protect frozen players from damage and hunger |
| `freeze.indicator` | `BOSS_BAR` | `BOSS_BAR`, `ACTION_BAR` or `BOTH` |
| `freeze.message` | `You have been frozen by staff. Please wait; staff will contact you in chat.` | Text of the indicator |
| `freeze.on_logout` | `NOTIFY_STAFF` | `NONE` or `NOTIFY_STAFF`. There is no punishment option. |
| `staffmode.gamemode` | `ADVENTURE` | `ADVENTURE` or `CREATIVE` |
| `staffmode.flight` | `true` | Allow flight in staff mode |
| `staffmode.vanish` | `true` | Vanish when entering staff mode |
| `staffmode.tools.compass` | `true` | Give the random-player compass |
| `staffmode.tools.inspect_wand` | `true` | Give the inspection wand |
| `staffmode.tools.freeze_rod` | `true` | Give the freeze rod |
| `staffmode.session_history` | `10` (1–100) | Finished staff-mode sessions kept per staff member |

## Inventory inspection

Online players are shown live: the menu works on the player's real inventory, not a copy, so nothing can be duplicated.

- **Layout:** rows 1–3 main inventory, row 4 hotbar, row 5 armour, offhand and the 2×2 crafting grid, row 6 info (health, food, XP, effects, game mode, buttons for the status panel and ender chest).
- **View mode:** nothing can be moved, whatever click or key is used.
- **Edit mode:** behaves like a chest. The crafting grid is always view-only.
- **Closing:** the menu closes when the player logs out, dies or respawns. Edit menus also close when the player enters staff mode.
- **Staff mode:** while you are in staff mode, every inspection menu is view-only. Players in staff mode cannot have their inventory or ender chest edited.
- **Audit:** each open, edit session and refusal is written to MejtaCore's audit log.

The status panel is a refreshing chest menu. A server cannot add a permanent on-screen overlay to an unmodded client.

## Offline inventories

**Viewing** reads the player's save from the current world (`playerdata/<uuid>.dat`) through the game's own loader. The file is never written. Viewing loads the save into a temporary player; the game may keep that player's statistics and advancements in memory until they join or the server restarts.

**Queued changes** (experimental, off by default) work like this:
1. **Never written directly.** MejtaUtils never writes another player's save. A change is stored as a pending change and applied when the player next joins, before their first tick.
2. **Checked first.** Each queued slot records the item it was made against. If the save changed in the meantime (a restored backup, an NBT editor, another server on the same world), the change is not applied. Your added items come back to you, and the player keeps everything.
3. **No duplication.** An added item leaves your inventory at once and is held in the change. A removed item stays with the player until the change is applied, then goes to your `/invsee claims` (or is deleted, if you chose that).
4. **One editor.** Only the staff member who started a change can edit it.
5. **Backups.** Before a change is applied, the player's inventory and ender chest are backed up (`invsee.offline.backups` per player).
6. **Crashes.** Each step is ordered so a crash can never duplicate an item. At worst, a crash at the wrong moment loses the one stack being moved, and the audit log records it. A change interrupted while applying is resolved on the next join: if the result is already there it is marked done, if the original is there it is applied, and otherwise nothing changes and staff are alerted. Use `pending discard` after checking such a player by hand.
7. **Staff-mode players.** Their real inventory is in the staff-mode save, so offline changes to them are refused.

A mod cannot lock out outside tools such as NBT editors. Point 2 detects their changes but cannot prevent them.

## Vanish

Vanished staff are hidden from players without `mejtautils.vanish.see`. Other Mejta addons (such as MejtaDiscord) also respect vanish through MejtaCore.

**Hidden:** the player entity and their projectiles; the tab list; the server list count and sample; join, leave and death messages; advancement announcements; command arguments, `/list` and name suggestions; sleeping counts; mob targeting, pushing and the Warden; pressure plates, tripwires, farmland, turtle eggs, redstone ore and big dripleaf; item, XP and arrow pickup (per setting); phantoms and raids; container animation, sound and redstone (silent containers); the locator bar; sculk and vibrations (experimental); sounds and block-break particles (experimental, partial: sounds without a source entity cannot be tied to the player).

Staff with `vanish.see` get a `[vanished]` copy of hidden join and leave messages.

**Not hidden:** natural mob spawning and chunk loading near you; riding a vehicle (dismount first); maps you carry; pets following you; trial spawners; advancement progress; other mods' player lists. Your own chat, attacks and door use reveal you, by design.

`/vanish fakequit` and `fakejoin` send vanilla's own leave and join messages, and are passed to MejtaCore presence listeners such as MejtaDiscord's chat relay.

## Freeze

- **Movement:** frozen players are held in place and hover (flight is granted while frozen). They can still look around, chat and open their own inventory.
- **Blocked actions:** breaking, placing, using items and blocks, attacking, dropping items, and commands not in `freeze.allowed_commands`, each as configured. The screen is never locked with a menu.
- **Indicator:** a boss bar and/or action bar message, plus a title when frozen.
- **Persistence:** freezes survive logouts and restarts, and are re-applied on join.
- **Logging out while frozen** notifies staff on `mejtautils.freeze`. The message says plainly that this is not proof of cheating. MejtaUtils never punishes anyone.
- **Diagnostics:** start, stop, disconnects, reconnects with ping, and blocked-action counts are logged to `mejta-data/mejtautils/freeze.jsonl`. `/freeze info` summarises them. No chat content or IP addresses are recorded.

## Staff mode

1. **Entering.** Your survival state is written to disk before anything changes: inventory, position and dimension, XP, health, food, air, fire, effects, game mode and abilities. If that write fails, staff mode is refused.
2. **While on.** Your inventory holds only the staff tools. Effects are cleared (vanish's night vision and flight speed are re-applied if you are vanished). You are invulnerable, in adventure (or creative) mode, with flight, and vanished if `staffmode.vanish` is on. Containers are view-only, and item pickup and drops are blocked. XP is zero, and dying drops nothing; the tools come back on respawn.
3. **Leaving.** Your inventory is replaced (never added to) from the saved state, and everything else is restored. A retried `/staffmode off` never hands items out twice. Items gained in creative are discarded.

**Recovery after a crash or disconnect** happens on your next join:

| Saved state | On the next join |
|---|---|
| Staff mode was active | Staff mode continues |
| Restoring had started | The restore is finished one tick after the join |
| Restore already done | The session is only closed; nothing is restored again |
| Entering, inventory not yet changed | The session is dropped; XP, game mode and abilities are reset |
| Entering, inventory already replaced by tools | Staff mode continues |
| Anything unclear, or a damaged save | Nothing changes. You are asked to run `/staffmode restore confirm`. |

`/staffmode restore confirm` is also needed when the saved dimension is gone or the game version changed. Holders of `mejtautils.staffmode.restore` are alerted on `mejtautils.staffmode`. The replaced inventory is recorded in the audit log.

**Tools:**

| Tool | Use |
|---|---|
| Compass | Teleport to a random player who is not staff (needs `staffmode.teleport`) |
| Inspection wand | Right-click a player for `/invsee`; shift-right-click for `/status` |
| Freeze rod | Right-click a player twice within 3 seconds to freeze or unfreeze |

Tools cannot be dropped or stored. A tool found outside an active staff mode is deleted.

## Data, audit and notifications

- **Data** is kept in `mejta-data/mejtautils/`. On a singleplayer or LAN world it is kept per world (under `store/worlds/<id>/`), so state does not carry over between worlds. Moving or renaming a world folder starts fresh.
- **Audit:** every sensitive action and every refusal is a MejtaCore audit entry (`/mejtaadmin audit recent`), also published on `mejtautils.audit`.
- **Alerts:** `mejtautils.freeze` (logout or rejoin while frozen), `mejtautils.invsee` (an offline change was applied, refused or interrupted) and `mejtautils.staffmode` (a recovery needs confirmation). MejtaDiscord can route these to Discord.

Nothing published contains IP addresses, chat content or secrets.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.4.0 first
cd ../mejta-utils && ./gradlew build             # compile and run unit tests
./gradlew runClientGameTest                      # client game test
```

- **Unit tests** cover offline-change logic, the staff-mode state machine, the freeze command filter and per-world data scoping.
- **The client game test** starts a dedicated server and joins it with a real, non-operator client. It checks that commands are hidden without permission; that view mode moves nothing and edit mode moves items exactly once; offline changes and their crash recovery; the vanish paths it can see with one client; freeze; and staff-mode restoration and recovery in every session state.

## Limitations

- What other players see of a vanished player (including the tab list) is not covered by the automated test, which uses one client.
- Advancement announcements are hidden through an optional hook. If a game update breaks it, the hook is skipped and announcements are shown.
- Queued offline changes are experimental and off by default.
- The status panel is a menu, not an on-screen overlay.
