# MejtaQoL

MejtaQoL (mod ID `mejtaqol`) adds everyday player commands to a server running **MejtaCore**:
- teleport requests (`/tpa`, `/tpahere`) and `/back`;
- homes, warps, `/spawn` and random teleport (`/rtp`), with paged menus for homes and warps (chests for everyone; players with MejtaCore can choose screens);
- your own ender chest, workstations and a trash bin anywhere (`/ec`, `/craft`, `/anvil`, `/trash`...);
- AFK status, `/r` for private messages, a join greeting and your death coordinates.

Every feature has its own on/off switch, and every command its own permission node. Limits, cooldowns and waiting times are all settings. Players need no client mod: menus are plain chests, and everything runs on the server.

| | |
|---|---|
| Version | 1.3.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.10.0 (any version from 1.10.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None |

## Installing

1. Put `mejtacore-1.10.0.jar`, `mejtaqol-1.3.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. The settings file `config/mejta/mejtaqol.json` is created on first start.
3. Players type `/qol` to see the commands they can use.

## Commands

Each command is also available as `/mejtaadmin mejtaqol <command>`, in case another mod already uses the name. You can turn off a top-level name with MejtaCore's `commands.disabled` setting in `mejtacore.json`. A command whose feature is switched off, or that the player lacks the permission for, is hidden from them.

| Command | Permission (`mejtaqol.` + ...) | What it does |
|---|---|---|
| `/qol` | none | The commands you can use right now, clickable |
| `/tpa <player>` | `tpa` | Ask to teleport to a player. They get clickable **[Accept]** and **[Deny]** buttons. |
| `/tpahere <player>` | `tpa` + `tpahere` | Ask a player to teleport to you |
| `/tpaccept [player]`, `/tpdeny [player]` | `tpa` | Answer the newest request, or the one from that player |
| `/tpacancel [player]` | `tpa` | Withdraw your requests (or the one to that player) |
| `/tptoggle` | `tpa` | Refuse or accept teleport requests (kept across relogs) |
| `/sethome [name]` | `home` | Save a home here (default name `home`). Setting an existing name moves it. |
| `/home [name]` | `home` | Go to a home. Without a name: the home called `home`, or your only home. |
| `/delhome <name>` | `home` | Delete a home |
| `/homes` | `home` | Your homes: a chest menu (left-click to go, shift-right-click to delete) or a clickable chat list |
| `/homes <player> [name [delete]]` | `home` + `homes.others` | Another player's homes, also offline players': list, visit or delete. Audited. |
| `/back` | `back` | Return to where you were before your last teleport, or where you died |
| `/spawn` | `spawn` | Go to the `/setspawn` point, or the world spawn if none is set |
| `/setspawn` | `setspawn` | Set the `/spawn` point to where you stand. Audited. |
| `/warp [name]`, `/warps` | `warp` | Go to a warp; without a name, list them (chest menu or chat list) |
| `/setwarp <name>` | `warps.manage` | Create or move a warp here. The item in your hand becomes its menu icon. Audited. |
| `/delwarp <name>` | `warps.manage` | Delete a warp. Audited. |
| `/rtp` | `rtp` | **Experimental.** Teleport to a random safe place in this dimension |
| `/enderchest`, `/ec` | `enderchest` | Open your own ender chest |
| `/workbench`, `/craft` | `workstation.crafting` | Crafting table |
| `/anvil` | `workstation.anvil` | Anvil (it never wears out) |
| `/stonecutter`, `/grindstone`, `/smithing`, `/loom`, `/cartography` | `workstation.<name>` | The matching workstation |
| `/trash` | `trash` | A chest whose contents are deleted when you close it |
| `/afk` | `afk` | Mark yourself as away, or back. Refused while in combat. |
| `/r <message>`, `/reply <message>` | `reply` | Private message to the last player you exchanged one with |
| `/mejtaadmin mejtaqol status` | `admin` | Open requests, searches, AFK players, warps, the spawn point and data scope |

## Permissions

All nodes start with `mejtaqol.`. Change their levels in `config/mejta/mejtacore.permissions.json`. Every action re-checks its permission when it happens; a teleport checks again when its wait ends.

| Node | Default level | Allows |
|---|---|---|
| `tpa`, `tpahere` | 0 (everyone) | Teleport requests |
| `home` | 0 | Homes |
| `homes.extra` | 1 (moderators) | `homes.max_extra` homes instead of `homes.max` |
| `homes.others` | 3 (admins) | List, visit and delete other players' homes |
| `back`, `spawn`, `warp`, `rtp` | 0 | The matching teleport |
| `setspawn`, `warps.manage` | 3 | Set the spawn point; create and delete warps |
| `enderchest`, `trash`, `afk`, `reply` | 0 | The matching command |
| `workstation.crafting`, `.anvil`, `.stonecutter`, `.grindstone`, `.smithing`, `.loom`, `.cartography` | 0 | The matching workstation |
| `afk.kick_exempt` | 1 | Only while `op_bypass.enabled` is on: never kicked for being idle |
| `teleport.bypass` | 1 | Only while `op_bypass.enabled` is on: teleport without waiting, cooldowns, the combat lock or the cross-dimension rule; send requests to players who refuse them |
| `admin` | 3 | `/mejtaadmin mejtaqol status` |

**Ranks.** MejtaCore's ranks (Member, Bronze, Silver, Gold) give more homes and a shorter `/rtp` cooldown, set per rank below. To make a command a rank perk, raise its level and give it to a rank in `mejtacore.rankperms.json`: for example `mejtaqol.workstation.anvil` to `MODERATORS` and `BRONZE`, and Bronze players and up keep `/anvil`.

## Settings

Stored in `config/mejta/mejtaqol.json`. Change them in the file, in MejtaCore's settings menu (`/mejtaadmin config menu mejtaqol` works for vanilla clients), or with `/mejtaadmin config set mejtaqol <key> <value>`. Changes apply at once; command lists are re-sent to every player.

| Key | Default | Meaning |
|---|---|---|
| `op_bypass.enabled` | `false` | Let `mejtaqol.teleport.bypass` and `mejtaqol.afk.kick_exempt` work. Off: operators follow the teleport rules and the idle kick like everyone else. |
| `teleport.warmup_seconds` | `3` (0–60) | Wait before every teleport. `0` = instant. |
| `teleport.cancel_on_move` | `true` | Moving more than half a block during the wait cancels it (looking around is fine) |
| `teleport.cancel_on_damage` | `true` | Taking damage during the wait cancels it |
| `teleport.cross_dimension` | `true` | Allow teleports into another dimension |
| `tpa.enabled` | `true` | Teleport requests |
| `tpa.here_enabled` | `true` | `/tpahere` |
| `tpa.timeout_seconds` | `60` (10–600) | Unanswered requests expire after this |
| `tpa.cooldown_seconds` | `3` (0–3600) | Time between two requests from one player |
| `homes.enabled` | `true` | Homes |
| `homes.max` | `3` (0–100) | Homes of a Member (a player without a rank) |
| `homes.max_bronze`, `homes.max_silver`, `homes.max_gold` | `5`, `6`, `7` (0–100) | Homes of a Bronze, Silver or Gold player ([MejtaCore ranks](../mejta-core/README.md#ranks)) |
| `homes.max_extra` | `10` (0–100) | Homes of a player with `homes.extra`, if more than their rank gives |
| `homes.cooldown_seconds` | `3` (0–3600) | Time between two `/home` teleports |
| `homes.blocked_dimensions` | empty | Dimensions where `/sethome` is refused, comma-separated (`the_end` means `minecraft:the_end`) |
| `homes.menu` | `true` | `/homes` opens a chest menu; off: a chat list |
| `back.enabled` | `true` | `/back` |
| `back.on_death` | `true` | `/back` also returns to where you died |
| `back.cooldown_seconds` | `5` (0–3600) | Time between two `/back` teleports |
| `spawn.enabled` | `true` | `/spawn` and `/setspawn` |
| `spawn.cooldown_seconds` | `5` (0–3600) | Time between two `/spawn` teleports |
| `spawn.on_first_join` | `false` | Put first-time players at the `/setspawn` point |
| `warps.enabled` | `true` | Warps |
| `warps.cooldown_seconds` | `5` (0–3600) | Time between two `/warp` teleports |
| `warps.menu` | `true` | `/warps` opens a chest menu; off: a chat list |
| `rtp.enabled` | `true` | **Experimental.** `/rtp` (see [Random teleport](#random-teleport)) |
| `rtp.min_radius`, `rtp.max_radius` | `500`, `5000` | Distance from the centre, in blocks |
| `rtp.center` | `spawn` | `spawn` (the world spawn) or `x,z` |
| `rtp.dimensions` | `minecraft:overworld` | Where `/rtp` works, comma-separated |
| `rtp.max_attempts` | `8` (1–32) | Places tried before giving up, one per tick |
| `rtp.cooldown_seconds` | `300` (0–86400) | Time between two `/rtp` uses of a Member |
| `rtp.cooldown_seconds_bronze`, `_silver`, `_gold` | `240`, `180`, `120` (0–86400) | The same for Bronze, Silver and Gold players |
| `enderchest.enabled` | `true` | `/enderchest` and `/ec` |
| `workstations.crafting`, `.anvil`, `.stonecutter`, `.grindstone`, `.smithing`, `.loom`, `.cartography` | `true` | Each workstation command |
| `trash.enabled` | `true` | `/trash` |
| `afk.enabled` | `true` | `/afk`, the AFK broadcast, the `[AFK]` tag and the idle kick |
| `afk.broadcast` | `true` | Tell everyone when someone goes AFK or comes back (never for vanished players) |
| `afk.tab_list` | `true` | Show `[AFK]` after AFK players' names in the player list |
| `afk.kick_minutes` | `0` (0–1440) | Kick players idle this long. `0` = never. |
| `afk.kick_message` | `You were idle for too long.` | Shown to a kicked player. `&` colour codes allowed. |
| `reply.enabled` | `true` | `/r` and `/reply` |
| `motd.enabled` | `false` | Greet players when they join |
| `motd.text` | `&6Welcome, {player}! &7Type &f/qol &7to see what you can do.` | The greeting. `{player}`, `{online}`; `&` colour codes. |
| `motd.first_join_broadcast` | `&e{player} joined for the first time. Welcome!` | Told to everyone on a first join (when `motd.enabled` is on). `{player}`. |
| `death.coordinates` | `true` | Tell a player privately where they died, with a **[Back]** button |

Messages use `&` codes: `&0`–`&9`, `&a`–`&f` for colours, `&l` bold, `&o` italic, `&n` underline, `&m` strikethrough, `&r` reset, `&&` for a plain `&`. Player names are inserted as plain text.

## Teleports

`/tpa`, `/home`, `/back`, `/spawn`, `/warp` and `/rtp` share the same rules:
1. **Combat lock:** after hitting or being hit by a player (also with arrows and other projectiles), teleports are refused while MejtaCore says the player is in combat: `combat.tag_seconds` in `mejtacore.json`, 10 seconds by default.
2. **Cooldown:** per feature, counted from the last successful teleport. Cooldowns are kept across relogs but not across restarts.
3. **Wait:** the player stands still for `teleport.warmup_seconds`; a countdown is shown above the hotbar. Moving or taking damage cancels it. A new teleport replaces a waiting one.
4. **Arrival:** the player leaves any bed or vehicle, fall damage is reset, and `/back` remembers where they were.

The destination is checked when the teleport is asked for and again when the wait ends: a home deleted, a warp removed or a `/tpa` partner who left in between cancels it with a message. Operators follow these rules too. Turn on `op_bypass.enabled` to let players with `mejtaqol.teleport.bypass` skip steps 1 to 3.

`/tpa` and `/tpahere` only find players the sender may see: vanished staff (MejtaUtils, or any mod using MejtaCore's presence service) are neither suggested nor found. Requests from or to a player who leaves are dropped silently.

## Homes and warps

Names use letters, digits, `_` and `-` (at most 16) and are not case-sensitive. The menus show up to 45 entries; more are reachable by name. Lowering `homes.max` never deletes homes: players keep them and can still move them, but can only add new ones once they are below the limit.

Staff with `mejtaqol.homes.others` can open any player's homes, also while that player is offline, with `/homes <player>`. Visits and deletions are written to MejtaCore's audit log.

## Random teleport

`/rtp` picks a random column between `rtp.min_radius` and `rtp.max_radius` from the centre (inside the world border), loads that chunk and looks down from the surface for solid ground with two free blocks above it. Water and lava surfaces, fire, magma, cactus, campfires, berry bushes, powder snow, wither roses, dripstone and bedrock (the Nether roof) are avoided. It tries one place per server tick, up to `rtp.max_attempts`; the normal wait applies once a place is found.

It is **experimental** because each try loads, or for new terrain generates, a chunk on the server thread, which can cause a short lag spike. Keep `rtp.max_attempts` low and the cooldown long on busy servers, or pre-generate the area.

## Ender chest, workstations and trash

`/ec` opens your own ender chest, the same container as an ender chest block. The workstations are the game's own menus: items left in the input slots go back to your inventory (or drop if it is full) when you close them, as at a real block. The portable anvil costs the usual XP but never wears out. Use `/trash` for items you want gone; they cannot be recovered after closing.

## AFK

MejtaCore decides who is AFK, so every addon agrees (MejtaSMP's AFK protection uses the same status). A player counts as active when they look around, walk, chat or interact with blocks, items or entities. Being moved by water or a vehicle does not count. After `afk.auto_minutes` (in `mejtacore.json`, 5 by default) without activity they are marked AFK; any activity ends it. `/afk` sets it by hand, but not while in combat, so it cannot be used to escape a fight. MejtaQoL adds the broadcast, the `[AFK]` tag and the idle kick. Vanished players' AFK changes are never announced.

## Private messages

`/r` answers the last player you exchanged a private message with through `/msg`, `/tell`, `/w` or `/r`. It uses the game's own private-message code, so messages are signed, filtered and shown exactly like `/msg`. If the last message was yours and the other player has since vanished, `/r` treats them as offline.

## Data and audit

- **Data** is kept in `mejta-data/mejtaqol/store/`: `homes/<uuid>`, `prefs/<uuid>` (whether the player accepts teleport requests), `warps` and `spawn`. On a singleplayer or LAN world it is kept per world (under `store/worlds/<id>/`). Teleport requests, cooldowns, `/back` points and AFK status are in memory only.
- **Audit:** `/setspawn`, `/setwarp`, `/delwarp` and staff visits to or deletions of other players' homes are MejtaCore audit entries (`/mejtaadmin audit recent`). Ordinary player teleports are not recorded.

Nothing logged or published contains IP addresses, chat content or secrets.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.10.0 first
cd ../mejta-qol && ./gradlew build               # compile and run unit tests
./gradlew runGameTest                            # headless server game test (about a minute)
```

- **Unit tests** cover locations, names and lists, message templates, cooldowns, teleport requests, home and warp books, and the random-teleport ring, and that every setting's default is valid.
- **The server game test** starts a headless server with MejtaCore and MejtaQoL, so both mixins must apply, and checks with fake players: every command is registered; `/sethome`, `/home` (and its cooldown), `/back` and `/delhome`; `/setwarp`, `/warp` and `/delwarp`; `/tpa` and `/tpaccept`, `/tptoggle`, `/tpahere` and `/tpdeny`, and the request cooldown; the `[AFK]` tag; that a PvP hit locks teleports and `/afk` until combat ends; that `/msg` is remembered for `/r`; the random-teleport safety scan on stone and lava; that a portable crafting grid gives its items back; the spawn point; and that a switched-off feature's commands become unavailable.

Not covered automatically, because fake players cannot open menus and are not in the player list: the chest menus, the timed wait and its cancellation, the AFK broadcast and kick, and `/rtp` end to end. Check these by hand on a test server.

## Limitations

- Permissions follow operator levels only (see [Permissions](#permissions)), so "more homes for donors" needs a level, not a group.
- Cooldowns, `/back` points and open requests are lost on restart.
- `/rtp` is experimental (see [Random teleport](#random-teleport)).
- Only MejtaQoL's own teleports are remembered for `/back`, not `/tp` or other mods' teleports.
