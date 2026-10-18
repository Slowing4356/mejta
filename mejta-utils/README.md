# MejtaUtils

MejtaUtils (mod ID `mejtautils`) is a server-side admin addon for **MejtaCore**. It provides inventory and ender-chest inspection (online and offline), a player status panel, vanish, freeze and staff mode.

It uses MejtaCore for everything shared:
- commands;
- settings;
- permissions;
- the data store;
- the audit log;
- notifications;
- server menus;
- presence.

| | |
|---|---|
| Version | 0.1.0 (first release; **not yet built or run**, see [Status](#status)) |
| Minecraft | 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | **MejtaCore 1.3.0 or newer, below 2.0.0** (API version 4) |
| Third-party libraries | None |

## Status

This release was written without access to the Minecraft jars, so it has **not been compiled or run yet**. Every Minecraft and Fabric name it uses was checked against these 26.1.x sources:
- Fabric API 0.155.3+26.1.2 (including its test mods);
- the 26.1.2 Vanish mod;
- sgui for 26.1;
- InvView for 26.1.

Names not found there are listed under [Things to verify](#things-to-verify). The pure logic is unit-tested: 16 JUnit tests cover offline changes, the staff-mode state machine and the command filter.

## Installing

1. Put `mejtacore-1.3.0.jar`, `mejtautils-0.1.0.jar` and Fabric API in the **server's** `mods/` folder.
2. Players need nothing: every menu is a plain chest, and all checks run on the server.

## Commands

All commands are top-level commands registered through MejtaCore. Each is also available as `/mejtaadmin mejtautils <command>`, in case another mod owns the name. You can switch off chosen names with `commands.disabled` in `mejtacore.json`.

| Command | Permission (default level) | What it does |
|---|---|---|
| `/invsee <player> [view\|edit]` | `invsee.view` (1); `edit`: `invsee.edit` (2) | The player's inventory. Online players are shown live; offline players from their save. |
| `/invsee <player> edit` (offline) | `invsee.offline_edit` (3) + `invsee.offline.edit` setting | Queue changes for an offline player (experimental) |
| `/invsee claims` | `invsee.view` (1) | Items waiting for you from applied or cancelled offline changes |
| `/ecsee <player> [view\|edit]` | `ecsee.view` (1); `edit`: `ecsee.edit` (2) | The player's ender chest (27 slots) |
| `/status <player>` | `status.view` (1) | Health, hunger, XP, effects and game mode. Clear effects (`status.effects`, 2) and change game mode (`status.gamemode`, 2), both with confirmation. |
| `/vanish [on\|off]`, `/vanish settings` | `vanish` (1) | Vanish, and personal vanish settings |
| `/vanish fakequit`, `/vanish fakejoin` | `vanish.fake` (3) + `vanish.fake_presence.enabled` | A pretend leave or join message (vanilla's own), also sent to MejtaCore presence listeners such as a Discord addon |
| `/freeze <player> [reason]`, `/unfreeze <player>` | `freeze` (1) | Freeze or unfreeze; offline players are frozen when they join |
| `/freeze list`, `/freeze info <player>` | `freeze.info` (1) | Frozen players, and what each one tried while frozen |
| `/staffmode [on\|off]` | `staffmode` (1) | Staff mode |
| `/staffmode restore confirm [player]` | `staffmode` (1); for others `staffmode.restore` (3) | Restore a saved state that could not be restored automatically |
| `/mejtaadmin mejtautils pending` | `invsee.offline` (1) | Queued offline changes |
| `/mejtaadmin mejtautils pending cancel <player>` | `invsee.offline_edit` (3) | Cancel an open change; added items go back to the staff member |
| `/mejtaadmin mejtautils pending discard <player>` | `admin` (3) | Delete a change record in any state, without moving items (after checking the player by hand) |
| `/mejtaadmin mejtautils sessions` | `staffmode.restore` (3) | Unfinished staff-mode sessions |

More nodes:

| Node | Default level | Effect |
|---|---|---|
| `mejtautils.vanish.see` | 1 | See vanished staff |
| `mejtautils.freeze.exempt` | 4 | Cannot be frozen |
| `mejtautils.staffmode.teleport` | 1 | Use the random-player compass |
| `mejtautils.protected` | 4 | Can only be inspected, edited or frozen by others who also have it |

All nodes are named `mejtautils.<…>`. Change their levels in `config/mejta/mejtacore.permissions.json`.

Every action re-checks its permission when it happens, not only when the command is typed. An open edit menu closes when the permission goes away.

## Settings (`config/mejta/mejtautils.json`)

| Key | Default | |
|---|---|---|
| `invsee.refresh_ticks` | 10 | Info row and status panel refresh |
| `invsee.offline.enabled` | true | View offline players (read-only) |
| `invsee.offline.edit` | **false** | **Experimental.** Queue offline changes; read [Offline inventories](#offline-inventories) first |
| `invsee.offline.backups` | 5 | Inventory backups per player before a queued change is applied |
| `vanish.default.item_pickup` | false | Defaults for new staff; each staff member can change their own in `/vanish settings` |
| `vanish.default.mob_targeting` | false | |
| `vanish.default.night_vision` | true | |
| `vanish.default.flight_speed` | 1.0 (1 to 4) | |
| `vanish.default.silent_containers` | true | |
| `vanish.persist_across_relog` | true | Rejoin vanished, with no join message |
| `vanish.hide_from_server_list` | true | Player count and names in the multiplayer list |
| `vanish.fake_presence.enabled` | false | Allows `/vanish fakequit`/`fakejoin` |
| `vanish.block_game_events` | true | **Experimental.** Sculk sensors, shriekers and the Warden ignore vanished staff |
| `vanish.filter_sounds` | true | **Experimental.** Hide sounds, block-break cracks and particles caused by vanished staff |
| `freeze.block_movement`, `freeze.block_item_drop`, `freeze.block_block_break`, `freeze.block_block_place`, `freeze.block_interact`, `freeze.block_attack`, `freeze.block_commands` | true | What a frozen player may not do |
| `freeze.allowed_commands` | `msg,tell,w,r` | Commands still allowed while frozen; chat is always allowed |
| `freeze.invulnerable` | false | Protect frozen players from damage, hunger and fire |
| `freeze.indicator` | `BOSS_BAR` | `BOSS_BAR`, `ACTION_BAR` or `BOTH` |
| `freeze.message` | "You have been frozen by staff…" | |
| `freeze.on_logout` | `NOTIFY_STAFF` | `NONE` or `NOTIFY_STAFF`. There is no punishment option. |
| `staffmode.gamemode` | `ADVENTURE` | `ADVENTURE` or `CREATIVE` |
| `staffmode.flight`, `staffmode.vanish` | true | |
| `staffmode.tools.compass`, `staffmode.tools.inspect_wand`, `staffmode.tools.freeze_rod` | true | |
| `staffmode.session_history` | 10 | Finished sessions kept per staff member |

## Inventory inspection

**Online players.** The menu is bound to the player's own inventory object, not a copy, so nothing can be duplicated. Two staff members and the player all act on one container on the server thread.

| Row | Content |
|---|---|
| 1 to 3 | Main inventory |
| 4 | Hotbar |
| 5 | Helmet, chestplate, leggings, boots, offhand, and the 2×2 crafting grid |
| 6 | Info: player, health, food, XP, effects, mode, status panel, ender chest, close |

- **View mode:** no slot can be changed. Vanilla's click handling is never called for it, so pick-up, shift-click, number keys, drag, double-click and drop all do nothing. The client view is re-sent after every click.
- **Edit mode:** behaves like a chest.
- **Crafting grid:** always view-only. The game returns those items to the inventory whenever the player closes their own inventory screen.
- **Closing:** the menu closes as soon as the player logs out, dies or respawns.
- **Audit:** each edit session is audited as a list of changed slots.

**In staff mode** every inspection menu is view-only. Items moved into a staff-mode inventory would be destroyed when staff mode ends.

**Status panel and the "top-right bar".** A server cannot add a permanent element to an unmodded client's screen. The vanilla client draws the top-right effect icons only for its own player. So the status panel is a live-refreshing menu. A permanent overlay would need a client-side mod. The server-side alternatives are a boss bar, the action bar or a scoreboard sidebar, each with its own drawbacks (see `INTEGRATION-DESIGN.md`, 3.6).

## Offline inventories

**Viewing** reads the player's save through the game's own player-data storage. That storage only reads `playerdata/<uuid>.dat` of the current world, and upgrades old data. The player is chosen with Minecraft's player argument; player input is never used as a file path. The save is never written.

**Queued changes (experimental, off by default).** Limitations, by design:

1. **Deferred.** MejtaUtils never writes another player's save file. A change is stored as a *pending change* (`mejta-data/mejtautils/store/pending/<uuid>.json`) and applied when the player next joins, before their first tick.
2. **Checked.** Each queued slot records the exact item it was made against. If the save changed in between (a backup was restored, an external NBT editor, a second server on the same world), the change is **not applied**. The items you added come back to you; the player keeps everything.
3. **No duplication by construction.**
   - **Removing:** a removed item stays in the player's save until the change is applied. Only then does it go to `/invsee claims` (or is deleted, if you chose that).
   - **Adding:** an added item leaves your inventory immediately and is held in the change. If the change is refused, you get it back.
4. **One editor per player.** Only the staff member who started a change can edit it.
5. **Crash windows.** Each transfer is ordered so that a crash between two steps can never duplicate an item:
   - giving to a change: take the item, save the players, then write the change;
   - taking from a change or a claim: write first, then give the item and save;
   - applying: mark it `APPLYING`, apply, save the players, then mark it applied.

   A crash in the milliseconds between two steps can lose (never duplicate) the one stack being moved. Its audit entry records it. A crash while `APPLYING` is resolved on the next join from the live inventory:
   - **after state found:** recorded as applied;
   - **before state found:** applied now;
   - **neither:** nothing is changed, and staff are alerted.
6. **Outside processes.** NBT editors or a second server can't be locked out by a mod. Point 2 detects them; it can't prevent them.
7. **Crafting grid.** Always empty offline; the game returns those items on logout.
8. **Staff-mode players.** Their real inventory is in the staff-mode save, not their player file. Offline changes to them are refused.
9. **Backups.** Before a change is applied, the player's inventory and ender chest are saved (`backups/<uuid>`, last `invsee.offline.backups`).

## Vanish

How it works:
- **Entity tracking.** Vanished players and their projectiles are hidden from the game's entity tracker for players without `vanish.see`. On toggling, the tracker is made to re-check at once.
- **Tab list.** Player-info packets are filtered per recipient at a single choke point. That covers joins, the latency and game-mode updates the server sends regularly, and display names.
- **Other addons.** Vanish state is MejtaCore's visibility provider, so other Mejta addons (for example a Discord chat relay) respect it.

**Leak paths and status.** "Covered" means a mixin handles it, using a target confirmed against the 26.1.2 Vanish mod. "Tested" means the gametest checks it from a real client.

| Path | Status |
|---|---|
| Entity visible to other players | Covered, tested (tracker decision) |
| Tab list | Covered; **not testable with one client** |
| Server list count and sample | Covered, tested (count) |
| Join and leave messages | Covered, tested (join). Staff with `vanish.see` get a `[vanished]` copy. |
| Command arguments (`/msg`, `/tp`…), `/list`, server-side name suggestions | Covered, tested (selector filter) |
| Death messages, advancement announcements | Covered. The advancement hook uses the lambda name `lambda$award$0`; if a game update renames it, it is skipped and logged. |
| Sleeping | Covered (counted like spectators) |
| Mob targeting and aggro, pushing | Covered, tested (husk) |
| Warden | Covered |
| Sculk sensors, shriekers, vibrations | Covered (`vanish.block_game_events`, experimental) |
| Pressure plates, tripwire, big dripleaf | Covered, tested (plate) |
| Farmland, turtle eggs | Covered, tested (farmland) |
| Redstone ore, sculk step | Covered |
| Item, XP and arrow pickup | Covered, tested |
| Projectiles hitting the player | Covered |
| Sounds and block-break particles caused by the player | Covered (`vanish.filter_sounds`, experimental). Sounds the game plays without a source entity can't be tied to the player: **partially covered**. |
| Block-break cracks | Covered |
| Phantoms, raids from Bad Omen | Covered |
| Containers (animation, sound, redstone, opener count) | Covered (silent containers) |
| Locator bar | Covered |
| Natural mob spawning and chunk loading near the player | **Not covered.** Fixing it would break staff work. |
| Riding a visible vehicle | **Not covered.** Dismount first. |
| Maps carried by the vanished player | **Not covered** |
| Pets following, trial spawners, advancement progress | **Not covered** |
| Command feedback shown to other operators | Not hidden (they are staff) |
| The player's own chat, attacks and door use | Reveal them, by design |
| Other mods' player lists | Not covered |

## Freeze

**What it does:**
- **Movement:** frozen players are held in place. They hover, because flight is granted while frozen, so they can't fall into constant corrections. They can still look around, chat and open their own inventory.
- **Blocked interactions:** breaking, placing, using items and blocks, attacking, dropping (the drop key, throwing from a screen, creative drops) and commands outside `freeze.allowed_commands`, each as configured.
- **Never:** the screen is never taken over with an unclosable menu.
- **Indicator:** a boss bar and/or action bar message. A title is shown when frozen.
- **Persistence:** freezes are stored, so they survive disconnects and restarts and are re-applied on join.
- **Unfreezing:** `/unfreeze` works for offline players too.

**Logging out while frozen** sends staff a notification (`mejtautils.freeze`) that says plainly it is **not proof of cheating**. MejtaUtils never punishes anyone. If a punishment addon is added later, it can listen to that notification and apply your own policy.

**Diagnostics** go to `mejta-data/mejtautils/freeze.jsonl` (staff only):
- start and stop;
- disconnects and reconnects, with ping;
- blocked-action counts, summarised by `/freeze info`.

No chat content or IP addresses are recorded.

## Staff mode

1. **Entering.** The survival state is captured and written to disk, synchronously and fsync'd, **before** anything changes. The state is: inventory with every item component, position and dimension, XP, health, food, air, fire, effects, game mode and abilities. If that write fails, staff mode is refused.
2. **In staff mode.**
   - The inventory is replaced by the tools.
   - The player is vanished (setting) and invulnerable, in adventure (or creative) mode, with flight.
   - Every real container is view-only, and picking up or dropping items is blocked. Items gained in creative are discarded when staff mode ends.
3. **Leaving.** The session is marked `RESTORING` on disk, then the inventory is **replaced** (never added to) from the snapshot. The players are saved, and only then is the session moved to the history.

**Recovery after a crash or disconnect:**

| Saved state | On the next join |
|---|---|
| `ACTIVE` | Staff mode continues |
| `RESTORING` | The replacement is repeated; doing it twice can neither lose nor duplicate items |
| `ENTERING`, inventory unchanged | The session is dropped |
| `ENTERING`, inventory only tools | Staff mode continues |
| Anything else, or a damaged snapshot | **Nothing changes**; the staff member is asked to run `/staffmode restore confirm` |

`/staffmode restore confirm` is also required when the saved dimension is gone or the game version changed. The replaced inventory is recorded in the audit log.

**Tools** carry a custom-data marker:

| Tool | Use |
|---|---|
| Compass | Teleport to a random player who isn't staff |
| Inspection wand | Right-click a player for `/invsee`; shift-right-click for `/status` |
| Freeze rod | Two right-clicks within 3 s to freeze or unfreeze |

Tools cannot be dropped or stored. Any tool found outside an active staff mode, in an inventory on join or as an item in the world, is deleted.

## Audit and notifications

Every sensitive action, and every refusal, is an entry in MejtaCore's audit log: actor, target, action, time, outcome and details (`/mejtaadmin audit recent`). Each entry is also published on `mejtautils.audit`.

Alerts that need attention are published on:
- `mejtautils.freeze` (logout or rejoin while frozen);
- `mejtautils.invsee` (an offline change was applied, refused or interrupted);
- `mejtautils.staffmode` (a recovery needs confirmation).

Nothing published contains IP addresses, chat content or secrets.

## Building and testing

```bash
cd ../mejta-core && ./gradlew build publishToMavenLocal     # MejtaCore 1.3.0
cd ../mejta-utils && ./gradlew build                        # compiles, runs the unit tests
./gradlew runClientGameTest                                 # end-to-end tests
```

The gametest starts a dedicated server, connects a real non-operator client and checks:

| Area | What is checked |
|---|---|
| Unauthorized use | Every command is unavailable without permission |
| Inspection | View mode moves nothing, under any click type; edit mode moves a stack exactly once; edit sessions are audited |
| Offline changes | Applied once, not twice; a changed save is refused with the item returned; each of the three interrupted-apply recoveries |
| Vanish | The paths in the table marked "tested" |
| Freeze | Walking, the drop key, a blocked and an allowed command, the logout notification, freeze kept after rejoin, unfreeze |
| Staff mode | Exact restoration; recovery after a disconnect in each session state; the confirmation path; tool removal |

Every check logs `[MejtaUtilsTest] PASS/FAIL`.

## Things to verify

These Minecraft names are used, but weren't found in the 26.1.x sources I could check. The first build will confirm them; each is a one-line fix if a name differs.

- `ItemStack.CODEC` with `encodeStart` and `parse`: item JSON.
- `Inventory.getSelectedSlot()` and `setSelectedSlot(int)`.
- `ChunkMap$TrackedEntity.updatePlayers(List<ServerPlayer>)` and the `ChunkMap.entityMap` field.
- `ServerboundSetCreativeModeSlotPacket.slotNum()`.
- `BuiltInRegistries.MOB_EFFECT.wrapAsHolder(...)`.
- `FoodData.setSaturation(float)`, `GameType.byName(String, GameType)`.
- `PlayerList.saveAll()`.
- `PermissionLevel` constants being declared in order from `ALL` to `OWNERS` (used by MejtaCore's offline permission check).
