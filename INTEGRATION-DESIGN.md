# MejtaUtils and MejtaDiscord: integration design

Status: **approved and implemented (2026-10-18), with the reduced Discord scope you chose.** Section 8 records your decisions and what was built; where it differs from sections 2 to 5, section 8 wins. None of it has been compiled against Minecraft yet: building and testing happen on your machine (decision 5).

The requests for `smp-utils` and `smp-discord` both say to inspect the real core API and settle the integration design before writing code. This document does that. It records:

- what MejtaCore 1.2.1 offers today and where the two addons need more;
- the core API I propose for MejtaCore 1.3.0 (the "core-first" rule);
- the design of each addon, with stable setting keys and permission nodes;
- the decisions only you can make (section 7).

Everything marked **verify** depends on Minecraft 26.1.2 or Fabric API 0.155.3 source code that I could not open in this environment (section 0.3). Those names are checked against the real jars before any code uses them; none is assumed.

---

## 0. Facts established first

### 0.1 Names

| In the request | In this repository | Folder | Mod ID (proposed) |
|---|---|---|---|
| `smp-core` | **MejtaCore** 1.2.1, API 3 | `mejta-core/` | `mejtacore` (exists) |
| `smp-utils` | **MejtaUtils** (admin tools; plan section 6) | `mejta-utils/` | `mejtautils` |
| `smp-discord` | **MejtaDiscord** (plan section 6) | `mejta-discord/` | `mejtadiscord` |
| `smp-perf` | **MejtaPerf** (planned, does not exist) | `mejta-perf/` | `mejtaperf` |

The mod IDs follow `mejtaac` and the plan's example provider ID `mejtadiscord`. The IDs are also the prefix of every permission node, notification channel and config file, so they must be fixed before the first release.

### 0.2 Target versions

| Item | Request text | Pinned in the repo | Status |
|---|---|---|---|
| Minecraft | "1.26.1.2" | **26.1.2** (unobfuscated, Mojang names) | "1.26.1.2" is not a real version. 26.1.2 matches your notes and both `gradle.properties` files. |
| Fabric Loader | "Fabric/API 19.5" | **0.19.5** | "19.5" is the loader's minor/patch. |
| Fabric API | "Fabric/API 19.5" | **0.155.3+26.1.2** | |
| Loom | | **1.18.4** (`net.fabricmc.fabric-loom`) | |
| Gradle | | **9.7.1** (wrapper) | |
| Java | | **25** | |
| JUnit | | **6.1.3** (approved, test-only) | |

The repo's `gradle.properties` says these were verified against `meta.fabricmc.net`. **I could not re-verify them from this environment** (section 0.3). The new addons copy the same values, so all Mejta projects stay identical.

### 0.3 Build environment (blocker for implementation, not for design)

This cloud session cannot build or test Fabric mods:

- `maven.fabricmc.net` and `meta.fabricmc.net` are refused by the network policy, so neither Loom nor Fabric API can be downloaded.
- The Mojang hosts (`piston-meta.mojang.com`, `libraries.minecraft.net`) are refused, so the Minecraft jar cannot be downloaded or decompiled.
- The installed JDK is 21; the project needs 25.

Maven Central and the Gradle plugin portal work.

Consequences:
- The Minecraft and Fabric method names that the mixins and events below rely on cannot be checked here. Writing that code blind would mean inventing APIs, which the requests forbid.
- Your rule "build and run the tests before claiming success" cannot be met here.

Implementation therefore needs one of these:
- this environment's network access widened to the Fabric and Mojang hosts, plus a Java 25 download;
- or the work done on your machine (`JAVA_HOME=.../jdk-25.0.4`).

See decision 5.

---

## 1. MejtaCore 1.2.1 today, against what the addons need

| Need (from the two requests) | Core API today | Gap |
|---|---|---|
| Addon registration, config, permissions | `MejtaAddon`, `AddonDefinition`, `ConfigSpec`/`Setting`, `MejtaPermission` (operator levels, overridable per node) | None. |
| Commands `/invsee`, `/vanish`, `/freeze`, `/staffmode`, `/ecsee` | Only `/mejtaadmin <addon> …` (`registerServerCommands`) | **No top-level commands.** Registering them with Fabric directly would bypass core, against "register commands through smp-core". → **B** |
| Persistence: vanish/freeze state, staff-mode sessions, pending offline edits, Discord account links | `RecordLog` (append-only JSONL) and `dataDirectory()` | **No atomic key/value store.** It was already planned for 1.3.0 (decision 5: JSON files). → **C** |
| Audit: actor, target, action, timestamp, outcome | Notifications and file log, written ad hoc by each addon | **No shared audit format.** → **H** |
| Vanish must also hide staff from Discord join/leave relays and future addons | None | **No presence API.** Without one, MejtaDiscord would relay a vanished admin's real join. → **E** |
| Discord buttons ("freeze player", "kick", "lag report") acting through other addons | None | **No way to invoke another addon's action without depending on it.** → **F** |
| Re-check a Discord user's linked Minecraft account, possibly offline | `hasPermission(CommandSourceStack)`, `hasPermission(ServerPlayer)` | **No offline check.** → **G** |
| Structured events (fake quit/join, violations) | Notifications only | **No event bus.** Planned for 1.3.0. → **D** |
| Webhook URLs and bot token | Secret-looking keys are hidden only in config-change log lines and notifications | **Secrets leak today.** See 1.1. → **A** |
| Discord delivery with retry and rate limits | `NotificationProvider.deliver` on one shared thread | A blocking retry (Discord can answer "retry after 30 s") would also stall the file log and in-game provider. → **J** |
| GUI pages for vanilla clients (status panel, confirmations, vanish settings) | Chest menus are **client-side** and need MejtaCore on the client | **No server-side menu.** → **I** |
| Experimental settings | Only mentioned in descriptions | Planned `experimental` flag. → **K** |

### 1.1 Secret leaks found in 1.2.1

All three are real paths. Today they matter only if someone stores a secret in a config, which MejtaDiscord would be the first to do:

1. **Server Settings sends every value to permitted clients.** `ServerConfigSync.describe()` puts `handle.get(setting)` into the payload, for every setting.
2. **`/mejtaadmin config get` prints every value**, including keys named `*token*` or `*webhook*`.
3. **`/mejtaadmin config set` echoes the new value and broadcasts it.** `ServerCommands.edit()` calls `sendSuccess(..., true)` with `result.message()` (`"<config>: <key> = <value>"`). With `true`, the message goes to every other online operator **and to the server log**, even though `ServerConfigSync.apply` hides the value in its own log line and notification.

An older client must also stay safe. A 1.2.x client handles each received setting with `SettingType.valueOf(type)`, so a new `SettingType` constant would make it throw. The proposal below therefore never sends a secret setting to any client and adds no new `SettingType` constant.

---

## 2. Proposed MejtaCore 1.3.0 (API 4) additions

Everything is **additive**. API 2 and 3 addons (MejtaAC 0.5.0, the test addon) keep working unchanged, and the test addon gains checks for each item.

| ID | API | Needed by | Priority |
|---|---|---|---|
| A | Secret settings | Discord | **First** (your instruction) |
| B | Top-level commands | Utils, (Discord admin) | Required |
| C | JSON data store | Utils, Discord, core mutes | Required |
| D | Event bus | Utils → Discord, AC → Discord | Required |
| E | Presence (visibility) | Utils, Discord, future QoL | Required |
| F | Action registry | Discord buttons/slash commands | Required for Discord's staff actions |
| G | Offline permission check | Discord | Required for Discord's staff actions |
| H | Audit log | Utils, Discord, core config edits | Required |
| I | Server-side menus | Utils (status, confirm, vanish settings), later corpse/QoL | Recommended |
| J | Per-provider notification queues, scheduler, bounded executor | Discord, Utils | Required (J1), planned (J2) |
| K | `experimental` flag | All | Planned |

### A. Secret settings

```java
public static final Setting<String> BOT_TOKEN = Setting.string("bot.token", "").secret()
    .describedAs("Discord bot token");
boolean Setting#isSecret();
```

- **A flag, not a new `SettingType`.** That keeps old clients and any `switch (type)` in addons safe.
- **Stored apart from the normal config:** `config/mejta/secrets/<id>.json`. On POSIX systems it is written owner-read/write only (`rw-------`); where that isn't supported, it is written normally and a one-time warning is logged. Sharing `config/mejta/<id>.json` for support then never shares secrets. (`.gitignore` already ignores `secrets/`.) A secret key found in the normal file is moved to the secrets file once, and that is logged without the value.
- **Never sent to clients:** Server Settings leaves secret settings out of the catalog entirely. Protocol 1 is unchanged, so 1.2.x clients are unaffected. An `EditConfig` packet targeting a secret key is rejected.
- **Commands:**
  - `config get` shows `(set)` or `(not set)`.
  - `config set` on a secret needs a new node `mejtacore.config.secrets` (default OWNERS) and answers "value hidden".
  - That answer is never broadcast to other operators (`sendSuccess(..., false)`).
  - The console is recommended for setting secrets, because a command typed in chat can show up in client logs.
- **Everywhere else:** the log line, notification, `Diagnostics` (including "invalid value replaced" warnings) and `toString` show `<hidden>`. The existing name regex stays as a backstop for settings not flagged as secret.
- **Fixes 1.1 (1) to (3)** for flagged and regex-matched keys alike.

### B. Top-level commands

```java
MejtaCoreAPI.registerRootCommand("mejtautils", "invsee", (root, buildContext) -> root
    .requires(MejtaCoreAPI.requires(UtilsPermissions.INVSEE_VIEW))
    .then(...));
```

- Registered by core in `CommandRegistrationCallback`; skipped if the addon is not active.
- **Collisions:** if the literal already exists in the dispatcher (vanilla or another mod) or another addon claimed it, core does not override it. It logs a diagnostic and the command stays reachable as `/mejtaadmin <addon> <literal>`.
- Server owners can disable or rename a root command in `config/mejta/mejtacore.commands.json`, for example to resolve a clash with another mod's `/vanish`.

### C. JSON data store

```java
DataStore store = MejtaCoreAPI.dataStore("mejtautils");
Optional<JsonObject> read(String key);
void write(String key, JsonObject value);                       // queued on the I/O thread
CompletableFuture<Void> writeDurable(String key, JsonObject v); // fsync'd before completing
void writeNow(String key, JsonObject value) throws IOException; // synchronous + fsync, for write-ahead
void delete(String key);
List<String> keys(String prefix);
static String playerKey(String prefix, UUID uuid);              // "staffmode/0b4c..." canonical UUID
```

- Files live in `mejta-data/<addon>/store/<key>.json`. They are written atomically (temp file, then move), with the last N generations kept as `.bak1…bakN`.
- **Keys are validated** (`[a-z0-9_-]+(/[a-z0-9_-]+)*`, at most 128 characters). Core resolves the path and checks it stays inside the addon's folder, so a key can never contain `..`, separators or drive letters. Callers never pass file paths.
- A corrupt file is renamed to `.broken-<millis>`, the newest valid backup is offered, and a diagnostic is logged. Nothing is silently reset.
- `writeNow` exists for MejtaUtils staff mode: the saved state must be on disk **before** an inventory is cleared.

### D. Event bus

Typed events across addons would force a shared class. MejtaDiscord cannot import MejtaUtils' classes without depending on it, so events are either **core-defined records** or a generic structured event:

```java
record MejtaEvent(String type, String source, Instant time, Optional<UUID> player, Map<String, String> data) { }
MejtaCoreAPI.events().publish(MejtaEvent);
MejtaCoreAPI.events().subscribe("mejtautils.presence", Delivery.SERVER_THREAD | Delivery.ASYNC, listener);
```

- Event types use the same pattern as channels (`<addon>.<name>`), and the publisher must own the prefix.
- Listeners are isolated: one failure does not affect the others. Their time is measured, and a slow server-thread listener (over 2 ms) is reported.
- Events and notifications differ:
  - **Notifications** are for humans: chat, Discord, file log.
  - **Events** are for code. A fake quit, for example, is an event (`mejtacore.presence`, see E) and also an audit entry (H).

### E. Presence (who is publicly online)

```java
interface PresenceService {
    boolean isHidden(UUID player);                      // vanished
    boolean canSee(ServerPlayer viewer, UUID target);   // vanish.see etc.
    void registerVisibilityProvider(String addonId, VisibilityProvider p);
    Collection<ServerPlayer> publiclyOnline(MinecraftServer server);
}
// Core publishes events "mejtacore.presence": data.kind = join | leave, data.real = true | false
```

- Core publishes the **public** join or leave: a real join of a vanished player is suppressed, and a fake quit or join is published with `real=false`.
- MejtaDiscord relays only these events, never raw Fabric join events. A vanished admin then never shows up in Discord, and `/vanish fakejoin` does. MejtaUtils never depends on MejtaDiscord, so the "future Discord provider" requirement is met.
- Future addons use `canSee` too, for example QoL's `/tpa` name suggestions.

### F. Action registry

This is how Discord buttons and slash commands act through other addons without depending on them:

```java
MejtaCoreAPI.actions().register(ActionDefinition.builder("mejtautils.freeze")
    .permission(UtilsPermissions.FREEZE)
    .param("target", ParamType.PLAYER)       // validated by core: online player or known UUID
    .param("reason", ParamType.TEXT, 200)    // length-limited, control characters stripped
    .destructive(false)
    .handler((actor, params) -> ...)         // runs on the server thread
    .build());

ActionResult r = MejtaCoreAPI.actions().invoke("mejtautils.freeze", actor, params);  // from any thread
record Actor(String displayName, Optional<UUID> minecraftAccount, String origin /* "discord:<user id>" */) { }
```

- Core does the security work, so no addon can forget it:
  1. Checks that the action exists and is active.
  2. Validates the parameters.
  3. **Re-checks the actor's Minecraft permission** (via G).
  4. Runs the handler on the server thread.
  5. Writes the audit record (H), whatever the outcome.
- `actions().list()` lets MejtaDiscord show only the buttons whose providers are installed. With no ore-log or perf provider, those buttons simply don't exist.
- Core itself registers a few vanilla-backed actions behind new nodes:
  - `mejtacore.kick`;
  - `mejtacore.whitelist.add`, `.remove` and `.list`;
  - `mejtacore.player.info` (read-only: online state, game mode, health, dimension; never IP addresses).

### G. Offline permission check

```java
boolean MejtaCoreAPI.hasPermission(MinecraftServer server, UUID account, MejtaPermission p);
```

- This resolves the operator level from the server's ops list for a player who may be offline. **Verify:** the 26.1.2 method that gives a profile's permission set; 1.21.9+ has a profile-based lookup on `MinecraftServer`.
- An unknown UUID is level 0. Console-only nodes never pass for a remote actor.

### H. Audit log

```java
MejtaCoreAPI.audit().record(AuditEntry.builder("mejtautils", "invsee.edit")
    .actor(source)              // name + UUID, or console, or Actor
    .target(uuid, name)
    .outcome(Outcome.SUCCESS)   // SUCCESS | DENIED | FAILED | CONFLICT
    .detail("slot", "12").detail("item", "minecraft:diamond x3")
    .build());
```

- One shared, append-only file: `mejta-data/mejtacore/audit.jsonl`, rotated like any record log.
- **Hash-chained:** each line carries the SHA-256 of the previous one, and `/mejtaadmin audit verify` reports any gap or edit. This is "immutable where practical": it can't stop someone with file access deleting the log, but it shows that it happened.
- Every entry is also published as a notification on `<addon>.audit`, so MejtaDiscord can route it.
- Core's own config changes and permission denials of sensitive commands use it too.

### I. Server-side menus (vanilla clients)

```java
ServerMenu.builder(Component title, int rows)
    .button(slot, () -> ItemStack, click -> ...)
    .refreshEvery(10)
    .open(serverPlayer);
ServerMenus.confirm(player, Component question, Runnable onConfirm);
```

- Built on vanilla `ChestMenu` (`GENERIC_9xN`). **Every click type is rejected for button slots:** pickup, quick-move, swap, clone, throw, pickup-all and drag. The menu is re-synced after every click, so no item can be taken. This is the single, tested place for anti-duplication rules.
- Used by: the status panel, the vanish settings page, confirmations, and the info row of `/invsee`.
- `/invsee`'s **real item slots** stay in MejtaUtils, because they are bound to a live inventory (section 3.4).

### J. Notification queues, scheduler, executor

- **J1, required.** One bounded queue and worker per provider, instead of one shared thread. A Discord provider waiting on a rate limit then can't delay the file log or in-game chat. The `NotificationProvider` interface is unchanged.
- **J2, planned.** As in plan section 3.2: a server-thread scheduler (`runLater`, `runEvery`) and one bounded executor per addon. MejtaDiscord's HTTP work and MejtaUtils' offline file reads use them.

### K. `experimental` flag

`Setting#experimental()` and `MejtaPermission`'s description show an "Experimental" marker in menus and in `config get`. Offline inventory editing (3.4) and several vanish leak controls (3.6) start as experimental.

### Also in 1.3.0, already planned

- Persistent notification mutes, using C.
- An optional `Notification.Builder.player(UUID, name)`, so Discord can show avatars without guessing field names. MejtaAC's existing `uuid` field is still read as a fallback.

---

## 3. MejtaUtils (`mejtautils`)

Server-only. Vanilla clients need nothing. The folder copies MejtaAC's layout: Gradle project, `src/main`, `src/test` (JUnit), `src/gametest` (client gametests) and a README.

### 3.1 Commands (all through B)

| Command | Permission | Notes |
|---|---|---|
| `/invsee <player> [view\|edit]` | `invsee.view`, `invsee.edit` | Online or offline (3.4). Default mode is `view`. |
| `/ecsee <player> [view\|edit]` | `ecsee.view`, `ecsee.edit` | 27 slots (3.5) |
| `/status <player>` | `status.view` | Status panel (3.6); also a button in `/invsee` |
| `/vanish [on\|off]` | `vanish` | |
| `/vanish settings` | `vanish` | Per-staff settings page (I) |
| `/vanish fakequit`, `/vanish fakejoin` | `vanish.fake` | Only if `vanish.fake_presence.enabled` is on |
| `/freeze <player> [reason]`, `/unfreeze <player>`, `/freeze list`, `/freeze info <player>` | `freeze`, `freeze.info` | |
| `/staffmode [on\|off]`, `/staffmode restore confirm` | `staffmode`, `staffmode.restore` | |
| `/mejtaadmin mejtautils sessions\|pending\|reload` | `admin` | Staff-mode sessions, pending offline edits |

**Player arguments:**
- Online players resolve through Minecraft's entity argument.
- For offline players, only a name matching `[A-Za-z0-9_]{1,16}` or a canonical UUID is accepted. A UUID must survive `UUID.fromString(s).toString().equals(s.toLowerCase())`.
- The name or UUID is resolved through the server's profile cache, then mapped to `<world>/playerdata/<uuid>.dat` by core-style path checks (normalise, then "starts with the world's player-data folder"). User text never becomes a path.

### 3.2 Permissions (stable nodes, operator-level defaults)

| Node | Default | Allows |
|---|---|---|
| `mejtautils.invsee.view` | MODERATORS | View online inventories |
| `mejtautils.invsee.edit` | GAMEMASTERS | Edit online inventories |
| `mejtautils.invsee.offline` | MODERATORS | View offline snapshots (inventory and ender chest) |
| `mejtautils.invsee.offline_edit` | ADMINS | Queue offline edits (experimental, 3.4) |
| `mejtautils.ecsee.view` | MODERATORS | View ender chests |
| `mejtautils.ecsee.edit` | GAMEMASTERS | Edit ender chests |
| `mejtautils.status.view` | MODERATORS | Status panel |
| `mejtautils.status.effects` | GAMEMASTERS | Clear effects (with confirmation) |
| `mejtautils.status.gamemode` | GAMEMASTERS | Change game mode (with confirmation) |
| `mejtautils.vanish` | MODERATORS | Vanish and its settings |
| `mejtautils.vanish.see` | MODERATORS | See vanished staff |
| `mejtautils.vanish.fake` | ADMINS | Fake quit and fake join |
| `mejtautils.freeze` | MODERATORS | Freeze and unfreeze |
| `mejtautils.freeze.info` | MODERATORS | Freeze diagnostics and alerts |
| `mejtautils.freeze.exempt` | OWNERS | Cannot be frozen |
| `mejtautils.staffmode` | MODERATORS | Staff mode |
| `mejtautils.staffmode.teleport` | MODERATORS | Random-player compass |
| `mejtautils.staffmode.restore` | ADMINS | Confirm an unsafe restoration for **another** staff member |
| `mejtautils.protected` | OWNERS | Can only be inspected, edited or frozen by actors who also have it |
| `mejtautils.admin` | ADMINS | Admin subcommands |

Every action re-checks permission at the moment it happens, not only when the command is typed. For example, an open edit GUI becomes view-only when the staff member loses `invsee.edit`. Denials of sensitive commands are audited (H).

### 3.3 Settings (stable keys, `config/mejta/mejtautils.json`)

| Key | Default | Notes |
|---|---|---|
| `invsee.refresh_ticks` | 10 | Info row refresh |
| `invsee.offline.enabled` | true | Offline **viewing** |
| `invsee.offline.edit` | false | **Experimental.** Offline editing (3.4) |
| `invsee.offline.backups` | 5 | Snapshots kept per player before a pending edit is applied |
| `vanish.default.item_pickup` | false | Default for new staff; each staff member can change their own |
| `vanish.default.mob_targeting` | false | |
| `vanish.default.night_vision` | true | |
| `vanish.default.flight_speed` | 1.0 (1.0 to 4.0) | Multiplier of vanilla flight speed |
| `vanish.default.silent_containers` | true | |
| `vanish.persist_across_relog` | true | Rejoin vanished, with no join message |
| `vanish.hide_from_server_list` | true | Player count and sample in the multiplayer list |
| `vanish.fake_presence.enabled` | false | `/vanish fakequit`/`fakejoin` are refused unless on |
| `vanish.block_game_events` | true | **Experimental.** Sculk, Warden (3.7) |
| `vanish.filter_sounds` | true | **Experimental** (3.7) |
| `freeze.block_movement` | true | |
| `freeze.block_item_drop` | true | |
| `freeze.block_block_break` | true | |
| `freeze.block_block_place` | true | |
| `freeze.block_interact` | true | Blocks, entities, item use |
| `freeze.block_attack` | true | |
| `freeze.block_commands` | true | |
| `freeze.allowed_commands` | `msg,tell,w,r` | Comma-separated; chat is always allowed |
| `freeze.invulnerable` | false | Protects from environmental damage while frozen |
| `freeze.indicator` | `BOSS_BAR` | `BOSS_BAR`, `ACTION_BAR`, `BOTH` |
| `freeze.message` | "You have been frozen by staff. Please wait; staff will contact you in chat." | |
| `freeze.on_logout` | `NOTIFY_STAFF` | `NONE` or `NOTIFY_STAFF`. There is **no punishment option** (3.8). |
| `staffmode.gamemode` | `ADVENTURE` | `ADVENTURE` or `CREATIVE`. Spectators cannot use items, so they can't use the tools. Creative allows item spawning, but those items are discarded on exit (3.9). |
| `staffmode.flight` | true | |
| `staffmode.vanish` | true | |
| `staffmode.tools.compass` | true | |
| `staffmode.tools.inspect_wand` | true | |
| `staffmode.tools.freeze_rod` | true | |
| `staffmode.session_history` | 10 | Restored sessions kept per staff member |

### 3.4 `/invsee`: online and offline

**Layout (54 slots):**

| Rows | Content |
|---|---|
| 1 to 3 | Main inventory, slots 9 to 35 |
| 4 | Hotbar, slots 0 to 8 |
| 5 | Helmet, chestplate, leggings, boots, offhand, then the four 2×2 crafting slots |
| 6 | Info buttons (I): head (name, UUID, online or offline, ping, dimension, coordinates), health, food, XP, effects, game mode, mode (view or edit), refresh, close |

**Online:**
- The item slots are **bound to the target's own inventory object**. They are not copies, so two staff members and the target all act on one container on the server thread and nothing can be duplicated.
- **Verify:** how 26.1.2 stores armor and offhand. Since 1.21.5 they sit in `Inventory`'s equipment rather than the old `armor` list.
- **View mode:** the slots refuse every pick-up and place. MejtaUtils' menu also rejects every click type for them on the server (as in I), then re-syncs.
- **Crafting slots are always view-only.** The vanilla server empties the 2×2 grid back into the inventory when the player closes their own inventory screen (**verify** in 26.1.2), so editing it would race the target.
- **Target logs out:** the staff GUI switches to the offline snapshot, view-only.
- **Target is in staff mode:** the GUI shows their **saved** inventory, view-only. The live one only holds tools.

**Offline viewing:**
- The player's `.dat` is read through the server's own player-data loading path, so vanilla's data fixer runs. **Verify:** `PlayerDataStorage`/`ValueInput` in 26.1.2.
- Files with a newer `DataVersion` than the running game are refused.
- The file is never written.

**Offline editing: limitations, before anything is built.** Editing an offline player's inventory is unlike editing a live one:

1. **It is deferred.** MejtaUtils **never writes another player's `.dat` file**. Edits are stored as a *pending change* in the data store (C) and applied when the player next joins, on the server thread, before their first tick. A player cannot join halfway through an edit: joins are processed on the server thread, between the edit and its commit.
2. **It is checked against what was edited.** A pending change records a hash of the snapshot it was made against. If the player's data changed in between (a world backup was restored, an external NBT editor, a second server on the same world), the change is **not applied**: it is quarantined and staff are notified.
3. **Duplication is impossible by construction (escrow).** In the offline GUI, staff cannot pick items up onto their cursor:
   - Removing an item moves it into **escrow**, inside the pending change. It is given to the staff member, or deleted if they chose "delete", **only after** the change has been applied to the player.
   - Adding an item takes it from the staff member immediately, into escrow. If the change is refused, it is returned to them.
   - An item therefore exists in exactly one place at all times, even if the change is refused or the server crashes. Escrow is written with `writeDurable`.
4. **One offline editor per player.** Other staff members get a view-only snapshot.
5. **The data must not be edited by anything else while the server runs.** Processes outside the server (NBT editors, a second server instance) cannot be locked out by a mod. Step 2 detects them, but cannot prevent them.
6. **Crafting slots are always empty offline.** Vanilla returns them to the inventory on logout (**verify**). They are shown as unavailable.
7. **Players who never joined have no data.** The command fails clearly.
8. **Before a pending change is applied**, the player's current inventory and ender chest are saved to the store's backups (`invsee.offline.backups`). `/mejtaadmin mejtautils pending` lists, applies or cancels changes.
9. **Default off:** offline editing starts **disabled and experimental**. Offline **viewing** is on.

### 3.5 `/ecsee`

The ender chest uses the same design, with 27 slots bound to the target's ender-chest container. If the target has that ender chest open at the same moment, both menus act on one container on the server thread. That is vanilla behaviour, so nothing is duplicated. Offline editing uses the same deferred, escrow-based change as 3.4.

### 3.6 Player status panel

A 3-row server menu (I), live-refreshed every `invsee.refresh_ticks`:

| Shows | Detail |
|---|---|
| Health and absorption | |
| Food and saturation | |
| XP | Level and progress |
| Active effects | Effect, amplifier and remaining time, in the item tooltip |
| Game mode | |

Actions:
- **Clear effects:** needs `status.effects`.
- **Change game mode:** needs `status.gamemode`.

Each action asks for confirmation (I), re-checks permission on confirm, is audited (H) and is published on `mejtautils.status`.

**About a permanent "top-right inventory bar":** a server cannot add a permanent HUD element to an unmodded client. The vanilla client draws the top-right effect icons only for its *own* player. These are the server-side options, and what each costs:

| Option | Where | Cost |
|---|---|---|
| Boss bar | Top centre | Text and one progress bar; per-viewer, so only the staff member sees it |
| Action bar | Above the hotbar | Must be re-sent about every 2 s; competes with other messages |
| Scoreboard sidebar | Right edge, middle | Per-viewer packets; conflicts with the server's own scoreboards |
| Client overlay | Anywhere, e.g. top right | Needs a MejtaUtils client part plus a network payload; only staff who install it would see it |

**Proposal:** the GUI only, in the first version. A boss-bar "follow" mode (`/status <player> follow`) can come later if you want it.

### 3.7 Vanish

**Mechanism:** a mixin at the entity-tracking pairing point (`ChunkMap.TrackedEntity`, **verify**) skips pairing a vanished player with viewers who lack `vanish.see`. Toggling vanish sends the add or remove packets. Tab-list entries are filtered per recipient at **one choke point**, the outgoing player-info packets. That covers joins, the latency and game-mode updates vanilla broadcasts periodically, and `/list`. Vanish state is persisted (C) and is provided to core presence (E).

**Leak audit.** Each path gets its own handling and its own gametest. **None is tested yet**, and the README will say which ones are not covered, rather than claiming perfect invisibility.

| Path | Vanilla mechanism (all **verify** in 26.1.2) | Handling |
|---|---|---|
| Entity visible | Entity tracker pairing | Skip pairing; also for projectiles, fishing hooks and items the vanished player owns or throws |
| Tab list | Player-info packets (add, latency, game mode, display name) | Filtered per recipient |
| Server list | Status response: player count and sample | Excluded (`vanish.hide_from_server_list`) |
| Query protocol (`enable-query`) | Query thread player list | Excluded, or documented if it can't be reached |
| Join and leave messages | Broadcast by the player list | Suppressed while vanished; fake ones only through `/vanish fake*` |
| Name suggestions and `/msg <name>` | Command suggestions, entity selectors | Vanished players filtered for sources without `vanish.see`, so "player not found" doesn't differ |
| `/list` | Player list | Filtered |
| Sleeping | Sleep status counts players | Vanished players excluded, so they don't block the night skip |
| Advancements | Announced in chat | Not announced while vanished (still granted) |
| Death messages | Broadcast | Not broadcast while vanished |
| Mob targeting and aggro | Targeting conditions, target goals, tempt goal | Rejected unless `mob_targeting` is on; current targets cleared on vanish |
| Warden | Vibrations, sniffing, anger | Covered by game events plus targeting; Warden anger towards the vanished player is cleared |
| Sculk sensors and shriekers | Game events with a source entity | Cancelled for vanished sources (`vanish.block_game_events`, experimental). This also stops steps, item use and block events from reaching sensors. |
| Pressure plates and tripwire | Entity checks that skip players ignoring block triggers | The vanished player ignores block triggers |
| Farmland | Trampling on fall | Cancelled |
| Turtle eggs, big dripleaf, redstone ore | Step and fall callbacks | Cancelled |
| Item and XP pickup | Item, XP-orb and arrow touch | Cancelled unless `item_pickup` is on |
| Pushing and projectiles | Pushable state; projectile hit checks | Not pushable; projectiles pass through |
| Sounds | Sounds sent to everyone *except* the source player (footsteps, eating, armour) | Recipients filtered (`vanish.filter_sounds`, experimental). Sounds vanilla plays with **no** source entity can't be tied to the player: **partially covered**. |
| Block-break cracks and particles | Level events and destroy progress, "except source" | Filtered like sounds |
| Containers | Lid animation, open sound, openers counter, container-open game event | Silent opening (`silent_containers`): the same container (no copy, so no duplication) without notifying the openers counter. Chests, trapped chests (no redstone signal), barrels, shulker boxes, ender chests. |
| Phantoms, raids | Insomnia statistic; Bad Omen raid start | No phantoms for vanished staff; no raid start |
| Natural spawning, chunk loading | Mobs spawn and farms run near any player | **Not covered.** Fixing it would break staff work; documented. |
| Vehicles | A ridden boat or minecart stays visible | **Not covered**; documented (dismount before vanishing) |
| Maps | A carried map shows its holder | **Not covered**; documented |
| Chat, attacks, interactions | The staff member's own actions | By design: a vanished staff member who chats, attacks or opens doors reveals themselves. Documented. |
| Discord and other Mejta addons | Join and leave relays | Through core presence (E): real joins of vanished staff are never relayed |
| Other mods | Their own player lists | Not covered; documented |

**Settings per staff member** (`/vanish settings`, a server menu): item pickup, mob targeting, night vision (a hidden effect, removed on unvanish), flight speed, silent containers.

**Fake quit and join:**
- Off unless `vanish.fake_presence.enabled` is on, and needs `vanish.fake`.
- A fake quit sends the vanilla leave message and tab removal to everyone else, and publishes the core presence event with `real=false`.
- Every use is audited.
- MejtaDiscord (if installed) relays it from the presence event. MejtaUtils never references MejtaDiscord.

### 3.8 Freeze

- **Movement:**
  - Transient attribute modifiers set movement speed to zero, jump strength to zero and gravity to zero (**verify** the attribute IDs). Transient modifiers are not saved with the player.
  - The vanilla client therefore doesn't move at all: no rubber-banding, and no fall while frozen mid-air.
  - Backstop: a server-side position lock (the mixin on move handling) teleports back on any change beyond 0.01 blocks, while rotation stays free.
  - The player is dismounted on freeze, and gliding is stopped.
- **Interactions** (each optional): block break and place, block and entity use, item use, attack, item drop, and commands except `freeze.allowed_commands`. These use Fabric's interaction and break events (**verify** module names in 0.155.3), plus mixins for item drops.
- **Not blocked:**
  - **Chat**, so the player can talk to staff.
  - Their **own inventory screen**.
  - **The screen is never hijacked.** There is no unclosable GUI.
- **Safety:** `freeze.invulnerable` (off by default) cancels damage while frozen. Hunger is paused while it is on.
- **Indicator:** a boss bar (and/or action bar) with `freeze.message`, plus a title when frozen and unfrozen.
- **Persistence:** frozen players are stored in C. After a disconnect or server restart, the freeze is re-applied on join and staff with `freeze.info` are notified ("rejoined while frozen"). `/unfreeze` also works on offline players.
- **Logout policy:** `freeze.on_logout = NOTIFY_STAFF` publishes a notification that says "logged out while frozen" and states plainly that it **is not proof of cheating**.
  - MejtaUtils never bans or punishes.
  - No punishment system exists in the ecosystem yet. If one is added, it can subscribe to the `mejtautils.freeze` event and apply *your* policy. That would be its own documented setting, not MejtaUtils'.
- **Diagnostics (staff only):** a `freeze` record log per session holds:
  - start and stop (actor, reason);
  - counts of each blocked action type;
  - ping samples;
  - disconnects and reconnects.

  `/freeze info <player>` shows a summary. Chat content is not recorded.
- **Exempt:** targets with `freeze.exempt`, and `protected` targets unless the actor is protected too.

### 3.9 Staff mode

**State machine, persisted in the data store (C):**

```
INACTIVE --/staffmode on--> ENTERING --> ACTIVE --/staffmode off--> RESTORING --> INACTIVE
```

1. **ENTERING.** The full survival state is captured. It is written with `writeNow`, synchronously and fsync'd, **before anything about the player changes**. If the write fails, staff mode is refused.

   It holds:
   - inventory, armour, offhand and ender-chest reference;
   - position, rotation and dimension;
   - XP level, progress and total;
   - health, food and saturation;
   - effects;
   - game mode and abilities (fly, flying, speeds);
   - fire and air.

   Each snapshot carries a SHA-256 of its content.
2. **ACTIVE.**
   - The inventory is cleared and the tools are given.
   - Vanish, game mode and flight are set, and the player is invulnerable.
   - The tools carry a custom-data tag `mejtautils:tool` with the session ID. They cannot be dropped, cannot be moved into any non-player slot, and are not dropped on death.
   - **Any tagged item found outside its active session is deleted and audited:** in a player inventory on join, as an item entity, or in a container when it is opened.
   - Every tool use re-checks its permission.
3. **RESTORING** is written first, then the inventory is **replaced** (never added to) from the snapshot. The player's data is saved (**verify**: the player-list save call), and only then is the session marked INACTIVE. A crash at any point re-runs the same replacement, so items can never be duplicated or lost.

**Crash and restart handling** (on join):

| Saved state on join | What happens |
|---|---|
| ACTIVE | The session continues: tools are re-issued |
| RESTORING | Restoration is repeated |
| ENTERING | If the inventory still equals the snapshot, the session is cancelled. If it holds only tools, it is ACTIVE. Otherwise it is **ambiguous**: nothing is changed and the staff member gets a summary and `/staffmode restore confirm`. Another staff member's restoration needs `staffmode.restore`. |

**Unsafe restoration also needs confirmation:**
- the saved dimension no longer exists (offer world spawn);
- the snapshot's hash doesn't match;
- the snapshot was written by a newer `DataVersion`.

**Items gained in staff mode are discarded on exit.** Restoration replaces the inventory, so creative-mode items can't leave staff mode. This is documented.

**Tools:**

| Tool | Right-click | Detail |
|---|---|---|
| Random-player compass | Teleports to a random publicly online player, not staff | Needs `staffmode.teleport`; logged |
| Inspection wand | A player opens `/invsee`; **shift**-right-click opens `/status` | |
| Freeze rod | Toggles freeze | A second click within 3 s confirms |

### 3.10 Notifications and audit

- **Notification channels:** `mejtautils.invsee`, `mejtautils.ecsee`, `mejtautils.status`, `mejtautils.vanish`, `mejtautils.freeze`, `mejtautils.staffmode` and `mejtautils.audit`. Every in-game audience is the matching staff permission.
- **No IP addresses, no chat content, no secrets.**
- Every sensitive action and every denied attempt produces an audit entry (H): actor, target, action, timestamp and outcome.

### 3.11 Tests

**JUnit (pure logic):**
- pending-change hashing, escrow bookkeeping and conflict detection;
- the staff-mode state machine, including recovery from each crash point;
- argument validation (names, UUIDs, path containment);
- settings parsing.

**Client gametests** (real client, dedicated server, as in MejtaAC; Fabric's fake player as the second player, **verify** its 0.155.3 module):

| Area | What the test does |
|---|---|
| Online and offline inventory conflicts | Two editors act on one live inventory. Edits are made offline, then the player joins: applied exactly once, item counts conserved. The `.dat` is tampered with between edit and join: refused, escrow returned, nothing duplicated. The server restarts with a pending change. |
| Vanish | One test per leak-audit row the harness can observe, measured from the client: no entity, no tab entry, no sound packet, plate not pressed, sculk not triggered, zombie doesn't target, sleeping counts, server-list sample. |
| Staff-mode restoration | Normal exit. The server is stopped while ACTIVE, and while RESTORING (by a test hook). A missing dimension needs confirmation. Tools can't be dropped or stored. |
| Unauthorized commands | A non-operator client runs every command and gets nothing. Raw container-click packets on a view-only menu change nothing. |

---

## 4. MejtaDiscord (`mejtadiscord`)

Server-only and optional:
- Nothing depends on it, and MejtaCore never references Discord.
- If it is absent, disconnected or misconfigured, every other addon behaves the same.
- Every notification is already in core's file log.

### 4.1 Connectivity, and the library question

Two kinds of connection are needed:

| Feature | Needs | Library? |
|---|---|---|
| Notifications to Discord, Minecraft-to-Discord chat, event embeds, performance alerts | **Webhooks** (outgoing HTTPS POST) | **None:** Java's `java.net.http.HttpClient` |
| Discord-to-Minecraft chat, `/verify`, buttons, slash commands, role checks | A **bot**: Gateway (WebSocket) for messages and interactions, plus REST | Either a library or a built-in Gateway client |

Discord's alternative to the Gateway for interactions is an HTTPS endpoint, which would need the game server publicly reachable over HTTPS. It is not proposed.

**Libraries evaluated** (Maven Central, checked 2026-10-18):

| Option | License | Maintenance | Size bundled | Notes |
|---|---|---|---|---|
| **JDA 6.7.0** | Apache-2.0 | Active (released 2026-09-20) | JDA 3.2 MB plus OkHttp 5.5 (+ Kotlin stdlib and Okio), Jackson 2.22, nv-websocket-client, Trove, commons-collections4, Tink. About 15 MB in total. The voice library opus-java can be excluded. | Most complete: rate-limit buckets, Gateway resume, interactions, member cache. Bundled via Loom `include` (jar-in-jar). Kotlin stdlib and Jackson can collide with other mods' copies. |
| Discord4J 3.3.3 | **LGPL-3.0** | Active (2026-08-19) | Larger (Reactor, Netty) | LGPL obligations for a bundled jar; reactive style. Not recommended. |
| Javacord 3.8.0 | Apache-2.0 | **Last release March 2023** | | Unmaintained. Rejected. |
| discord-webhooks 0.8.4 | Apache-2.0 | Last release 2023 | | Unnecessary: `HttpClient` does the same. Rejected. |
| **Built-in client** | Ours | Ours | 0 MB | `java.net.http.WebSocket` and `HttpClient` plus Gson (shipped with Minecraft). Only the narrow subset needed: Hello, Heartbeat, Identify, Resume, Reconnect, Invalid Session, and the dispatches `READY`, `MESSAGE_CREATE`, `INTERACTION_CREATE` and `GUILD_MEMBER_*`. REST for commands, interaction responses and member lookups. Estimated 1,500 to 2,000 lines, tested against a local fake Gateway and REST server. |

**Recommendation:**

- **Phase 1 is webhooks only, with no library.** It covers notifications, Minecraft-to-Discord chat, embeds and performance alerts, and can be built as soon as core 1.3.0 exists.
- **For phase 2 (the bot), my recommendation is the built-in client.**
  - The feature set is narrow.
  - It avoids about 15 MB of bundled dependencies (Kotlin, Jackson, OkHttp) on a shared mod classpath.
  - It matches your no-library preference.
  - The cost is that we maintain the Gateway code ourselves.
- **JDA is the robust alternative** if you'd rather not. **Decision 2.**

The fake Gateway used in tests needs a small WebSocket server. The JDK has no WebSocket server, so the tests would include about 150 lines of their own instead of a test library.

**Connection handling:**

- **Status** is shown in `/mejtaadmin mejtadiscord status`: per webhook (OK, rate-limited, failing or disabled) and for the bot (connected, resuming, disconnected, and the reason).
- **Reconnects:** exponential backoff with jitter, Gateway resume, and a fresh identify only when Discord requires it.
- **Webhooks rate limits:**
  - Discord's `X-RateLimit-*` headers and `retry_after` are honoured, per webhook and globally.
  - Each webhook has a bounded queue (200). On overflow, the oldest INFO notifications are merged into a single "+N more" line; WARNING and CRITICAL are kept.
  - 5xx and network errors retry up to 5 times with backoff.
  - 401 or 404 (webhook deleted) disables that webhook and alerts staff in-game.
  - Other 4xx are not retried.
- **All HTTP is asynchronous,** on MejtaDiscord's own threads (J2). `NotificationProvider.deliver` only routes and enqueues, then returns, so no other provider waits.

  This differs from your note that blocking HTTP is fine in `deliver`: it would be allowed, but in 1.2.1 it is one thread shared with the file log and in-game chat. J1 fixes that in core as well.
- **Safe shutdown:** on server stop, queues get 5 s to drain. The bot sends a Gateway close. Anything left is already in core's file log.

### 4.2 Secrets

- Bot token and webhook URLs are **secret settings (A)**, kept in `config/mejta/secrets/mejtadiscord.json` and outside the repo.
- **Never logged:**
  - `HttpClient` exceptions can contain the request URL, so every log line and diagnostic passes through a redactor. It removes `discord.com/api/webhooks/...`, the token pattern and the configured secret values themselves.
  - Raw exception messages are never logged.
- `/mejtaadmin mejtadiscord test <route>` sends a test message and reports the outcome, never the URL.
- The README's example configuration shows `"<set with /mejtaadmin config set ...>"`, never a real-looking value. Nothing is hardcoded: no invite links, codes or IDs.

### 4.3 Routing (notification channels to webhooks)

Settings are fixed keys, but notification channels are open-ended, so routing uses **named webhook slots** plus a routing table:

| Key | Type | Example |
|---|---|---|
| `webhook.default.url` | secret | |
| `webhook.slot1.url` … `webhook.slot8.url` | secret | |
| `webhook.slot1.name` … | string | `ac-alerts` |
| `routing` | string | `mejtaac.alert=ac-alerts; mejtaac.enforcement=ac-kicks; mejtacore.config=staff-log; mejtautils.*=staff-log; *=default` |
| `routing.min_severity.<slot>` | | Optional |

Channels exist today:

| Channel | Content |
|---|---|
| `mejtaac.alert` | Anti-cheat flags |
| `mejtaac.enforcement` | Kicks and would-be kicks |
| `mejtacore.config` | Config changes |

MejtaUtils' channels are listed in 3.10. An unrouted channel without a `*` rule is not sent anywhere.

### 4.4 Embeds and events

**Notification embeds:**

| Notification | Embed |
|---|---|
| Title, message | Title, description |
| Severity | Colour: INFO blue, WARNING orange, CRITICAL red |
| Fields | Inline fields, up to Discord's 25 |
| Source and channel | Footer |
| Timestamp | Timestamp |

**Events MejtaDiscord observes itself** (vanilla or Fabric, no addon involved; each one can be toggled):

| Event | Source |
|---|---|
| Server starting, started, stopping | Lifecycle events |
| Server availability | A webhook message at start and stop; the bot status |
| Deaths and PvP kills | Living-entity death event: the death message, and the killer if a player |
| Milestones | Advancement awards with chat announcements (**verify**: Fabric has no advancement event, so this needs a mixin on the award method). Only advancements vanilla itself announces are sent. |
| Dragon kills | Ender dragon death, with the players in the fight area |
| Join and leave | **Only from core presence (E)**, so vanish is respected |

**Usernames and avatars:**

- Webhook messages can set `username` (template, default `{name}`) and `avatar_url`.
- Mojang has no official avatar API, and using a third-party render service sends player UUIDs to that third party.
- So `chat.avatar_url_template` is **empty by default** (the webhook's own avatar is used). The README lists services (for example mc-heads.net, crafatar.com) for owners to choose, with a note to check each one's terms.

**Item sharing:**

- `[item]` in chat posts the held item's name, lore and enchantments as an embed. The format is configurable.
- The README says plainly that this is a summary, not a Minecraft tooltip: no icons, no hover, no components beyond those three.

**Chat:**
- Minecraft to Discord, over the webhook.
- Discord to Minecraft, through the bot, from one mapped channel. This needs the privileged MESSAGE_CONTENT intent, enabled in the Developer Portal.

**Safeguards (both directions):**

- **Mentions are disabled** on every outgoing message (`allowed_mentions: {"parse": []}`). `@everyone`, `@here`, and user or role mentions are also neutralised in the text.
- Markdown is escaped from Minecraft.
- `§` codes and control characters are stripped.
- Length limit 256 characters (Discord to Minecraft) and 2,000 (Minecraft to Discord).
- Rate limit per Discord user and per Minecraft player.
- Optional word filter (a list setting).
- **Loop prevention:** messages from webhooks, bots and our own application ID are ignored.

### 4.5 Account verification and role sync

Everything here is off by default, and each part is independently configurable.

| Key | Default |
|---|---|
| `verification.enabled` | false |
| `verification.required_to_join` | false |
| `verification.code_ttl_seconds` | 600 |
| `verification.max_attempts` | 5 per Discord user per 10 min |
| `verification.required_role_id` | "" |
| `verification.invite_url` | "" (nothing hardcoded) |
| `verification.sync_nickname` | false |
| `access.revoke_on_role_loss` | false |
| `access.revoke_on_leave` | false |
| `access.revoke_grace_minutes` | 60 |

**Codes:**
- 8 characters from a 31-character alphabet with no lookalike characters (`0/O`, `1/I/l` removed), from `SecureRandom`: about 40 bits.
- Single-use, expiring, and stored only as a SHA-256 hash with a per-boot pepper. The plain code exists only in the player's message.
- One live code per player. A new code invalidates the old one.

**Flow:**

1. **At login:**
   - With `required_to_join`, an unlinked player is disconnected **during login** with a screen showing the code and the configured invite (**verify**: Fabric's login-phase events).
   - Otherwise they get a clickable chat message.
2. In Discord, `/verify code:<code>` answers with an ephemeral message, visible only to them.
3. The bot checks two things **from the interaction itself**, which is fresh: that the user is a member of the configured guild, and that they hold the required role.
4. The link is stored in C (`links/<uuid>` plus a reverse index). It is audited (H) and published on `mejtadiscord.verification`.
5. If enabled, the nickname is synchronised. This needs Manage Nicknames, and Discord never allows it for the server owner; that failure is reported, not retried.

**Brute force:**
- 5 attempts per Discord user per 10 minutes, and 30 per minute globally.
- With about 2⁴⁰ codes, a TTL of 10 minutes and those limits, guessing is infeasible.
- Failed attempts are audited, rate-limited.

**Duplicate accounts:**
- A Discord account links to one Minecraft UUID, and the reverse.
- Re-linking needs an unlink first (by an admin, or `/unlink` by the player). An attempt to link a second account is refused and audited.

**Revocation** (only if the owner turns it on):
- Checked periodically by REST, and from member events if the GUILD_MEMBERS intent is enabled.
- **Never on an API error or outage.** Revocation needs a successful, definite "not a member" or "role missing" answer.
- After the grace period, the player is denied **at their next login** with a message. They are never kicked mid-session and never banned.
- Staff are notified before it takes effect.

**Administrator recovery:**

| Command | Purpose |
|---|---|
| `/mejtaadmin mejtadiscord link <player> <discord_user_id>` | Link by hand |
| `/mejtaadmin mejtadiscord unlink <player>` | Remove a link |
| `/mejtaadmin mejtadiscord links` | List links |
| `/mejtaadmin mejtadiscord verification off` | Equals setting `verification.required_to_join=false`; works from the console |

Anyone with `mejtadiscord.verification.bypass` (default OWNERS) can always join, so a broken bot can't lock the owner out. Every recovery step is in the security guide.

### 4.6 Staff alert centre and actions

- An alert channel mapped by `routing`. It receives `mejtaac.alert`, `mejtaac.enforcement` and the admin events.
- **What MejtaAC publishes today:** player, UUID, check, reason, confidence, VL, ping, world, action, mode, kick message.
- **What it doesn't:**
  - coordinates and TPS/MSPT; those would be a small separate MejtaAC change;
  - xray investigations, ore logs and punishments, because no addon provides them.

  The embeds show only what the event provider sent. MejtaDiscord adds the server's MSPT and TPS **at delivery time**, labelled as such.
- **Buttons** appear only for actions that exist in the core action registry (F):
  - **Freeze player** (`mejtautils.freeze`, if MejtaUtils is installed).
  - **Kick** (`mejtacore.kick`, destructive, so it needs confirmation).
  - **Recent ore logs** and **performance report**, only once an addon registers them. None does today, so these buttons do not appear.

**Every click goes through these checks:**

1. The `custom_id` is `mejta:<nonce>`. The nonce maps on the server to an action, target and expiry (15 minutes, Discord's interaction-token lifetime), and is **single-use**. Replayed or expired clicks get an ephemeral "expired".
2. The Discord user's roles **from the interaction payload** are mapped to allowed actions (`roles.<action>`).
3. The user must have a **linked** Minecraft account, and core re-checks that account's Minecraft permission (F plus G).
4. The target is re-validated: core validates it, and the action's own rules apply (for example `protected`).
5. Destructive actions show an ephemeral **Confirm** button with a second nonce, valid for 60 s.
6. Core writes the audit entry, whatever the outcome. The reply is ephemeral; the result is also posted in the staff channel.

### 4.7 Performance alerts

| Key | Default |
|---|---|
| `perf.alerts.enabled` | false |
| `perf.mspt_threshold` | 50 |
| `perf.sustain_seconds` | 30 |
| `perf.cooldown_minutes` | 10 |

- The server's average tick time is sampled every second (**verify** the 26.1.2 accessor).
- **An alert shows:**
  - MSPT and TPS over the window;
  - player count;
  - loaded chunks per dimension;
  - entity counts per dimension, and the top entity types.

  These are labelled "available metrics; possible contributors". There is **no claim that a specific chunk or player is the cause**.
- If an addon registers `mejtaperf.report` or a throttling action in F, a button appears. MejtaPerf doesn't exist, so the controls are absent, and that is reported in `status`.

### 4.8 Remote administration

- Off by default (`remote.enabled=false`).
- **Slash commands map only to registered core actions:** no console, no arbitrary commands, no shell.
- **First set:**
  - `/mc whitelist add|remove|list`
  - `/mc kick <player> <reason>` (confirmation)
  - `/mc freeze <player>` (if MejtaUtils is installed)
  - `/mc player <player>` (read-only info)
  - `/mc setting get|set <key>`, only for keys in `remote.settings_allowlist`, which is empty by default. Secret settings can **never** be allowlisted.
- **Access:** roles map to actions (`roles.<action>`), the user must be linked, and core re-checks the Minecraft permission. That is least privilege: both sides must allow it.
- Per-user rate limit, and every invocation is audited (H, hash-chained).

### 4.9 Settings and permissions summary

**Setting groups** (all keys stable, listed in the README):
- `connection.*` (mode `OFF`, `WEBHOOK` or `BOT`)
- `bot.*`
- `webhook.*`
- `routing*`
- `roles.*` (Discord role ID to action)
- `notify.*` (per-event toggles)
- `chat.*` (formats, templates, relay toggles, filters)
- `verification.*` and `access.*`
- `perf.*`
- `remote.*`
- `retry.*` (max attempts, backoff, queue size)

**Permission nodes:**

| Node | Default | Allows |
|---|---|---|
| `mejtadiscord.admin` | ADMINS | Status, test, link management |
| `mejtadiscord.verification.bypass` | OWNERS | Join without verification |
| `mejtadiscord.status` | MODERATORS | Read-only status |

### 4.10 Tests

**JUnit, with a fake Discord built on the JDK's `com.sun.net.httpserver` and a small test WebSocket server:**

| Area | Cases |
|---|---|
| Webhooks | Delivery and ordering; 429 with `retry_after`; global limit; 5xx retry and give-up; 404 disables the route |
| Bot connection | Disconnect and resume; invalid session; reconnect backoff |
| Verification | Expired codes, reused codes, brute-force limits, duplicate accounts, invalid or missing roles |
| Revocation | Never on outage |
| Actions | Replayed and expired interactions; unauthorised Discord users; linked accounts without the Minecraft permission; actions that aren't installed (graceful absence) |
| Safety | Mention neutralisation; loop prevention |
| Secret redaction | Every log path, exceptions included, never contains a webhook URL or token |

**Client gametest:** MejtaDiscord installed but unconfigured, or failing, while MejtaAC and MejtaUtils run normally. Real Discord is not tested automatically. The README has a manual checklist.

**Deliverables:**
- installation instructions;
- Discord application setup (scopes `bot applications.commands`; permissions View Channel, Send Messages, Embed Links, optional Manage Nicknames; the privileged intents and when each is needed);
- a permission and role-mapping guide;
- a security guide.

---

## 5. Order of work

| Step | Content | Tests that must pass |
|---|---|---|
| 1 | **MejtaCore 1.3.0, part 1:** secrets (A) and the three leak fixes, `experimental` (K) | Test addon gametests (148 + new) |
| 2 | **MejtaCore 1.3.0, part 2:** B, C, D, E, F, G, H, I, J; persistent mutes | Test addon gametests; old-API addon compatibility; MejtaAC suite unchanged |
| 3 | **MejtaUtils 0.1.0:** online `/invsee`, `/ecsee`, status, freeze, vanish (all leak-audit rows), staff mode, offline viewing | JUnit and gametests from 3.11 |
| 4 | **MejtaUtils 0.2.0:** offline editing (experimental, off by default) | Conflict suite from 3.11 |
| 5 | **MejtaDiscord 0.1.0:** webhooks, routing, embeds, Minecraft-to-Discord chat, events, performance alerts (as built: see 8.3) | JUnit and gametests from 4.10 |
| 6 | **MejtaDiscord 0.2.0:** bot (after decision 2): Discord-to-Minecraft chat, verification, buttons, slash commands, remote admin | JUnit and gametests from 4.10 |

Each step ends with its README, the test results actually run, and a commit.

## 6. What this does not change

- Nothing in MejtaCore or MejtaAC is removed or renamed.
- No addon depends on another.
- No library is added without your approval.
- No version changes.

## 7. Decisions needed

1. **Core API:** approve section 2 (A to K), or tell me which parts to drop or change.
2. **Discord bot:** built-in Gateway client (recommended), or JDA 6.7.0 bundled (about 15 MB, Apache-2.0)? Phase 1 (webhooks) needs neither.
3. **Order:** the plan puts MejtaPerf (M4) and MejtaAC 0.6 (M5) before the addons (M6). Should core 1.3.0 then MejtaUtils and MejtaDiscord go first, as section 5 assumes?
4. **IDs and commands:**
   - mod IDs `mejtautils` and `mejtadiscord`;
   - the command names in 3.1 (`/status` especially; it's a generic name);
   - the permission defaults in 3.2.
5. **Build environment:** allow `maven.fabricmc.net`, `meta.fabricmc.net` and the Mojang hosts (`piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`) in this environment, plus a Java 25 download? Or should implementation happen on your machine? Without one of these, no code can be compiled or tested.

## 8. Decisions taken, and what was built

Your answers to section 7:

1. **Core API:** approved, with the instruction to improve it.
2. **Discord scope, reduced:** MejtaDiscord only sends anti-cheat messages to Discord and relays chat both ways. Bot connection: the built-in Gateway client (no library).
3. **Order:** core, then MejtaUtils, then MejtaDiscord, then performance (MejtaPerf). MejtaAC already exists.
4. **IDs and commands:** as proposed.
5. **Build environment:** builds and tests run on your machine.

### 8.1 MejtaCore 1.3.0 (API 4)

| Part | State |
|---|---|
| A. Secret settings, and the three leak fixes | Built |
| B. Top-level commands | Built |
| C. JSON data store | Built |
| D. Event bus | **Deferred.** Nothing in the reduced scope needs it: MejtaDiscord subscribes to notifications, which already carry MejtaAC's events. |
| E. Presence | Built |
| F. Action registry | **Deferred.** It was for Discord buttons and remote actions, which are out of scope. |
| G. Offline permission check | **Partly built.** `MejtaCoreAPI.hasPermission(MinecraftServer, NameAndId, MejtaPermission)` exists. The rest is deferred with F. |
| H. Audit log | Built |
| I. Server-side menus | Built |
| J. Notification queues and scheduler | Built |
| K. `experimental` flag | Built |

20 JUnit tests cover secrets, the data store and the audit chain. They pass on Java 21 here. The test addon's gametest has new checks for API 4.

### 8.2 MejtaUtils 0.1.0

Built as described in section 3. Offline editing is included, experimental and off by default.

The 16 JUnit tests pass here. The client game test is written but has not been run. Names not found in the reference sources are listed under "Things to verify" in `mejta-utils/README.md`.

### 8.3 MejtaDiscord 0.1.0

Built in the reduced scope:

- **Notifications:** MejtaCore notifications go to up to three webhooks (`default`, `alerts`, `staff`), chosen by routing rules, as embeds with a colour for each severity. The default route is `mejtaac.*=default`.
- **Minecraft to Discord:** chat goes through a chat webhook, under each player's name. Join and leave messages are optional and respect vanish.
- **Discord to Minecraft:** messages are read through a built-in, read-only Gateway v10 client. It needs only View Channels, Read Message History and the Message Content intent. Messages are shown as plain text, with a rate limit per user and a word filter.

Sections 4.5 to 4.8 are **not built**: account verification, role sync, the staff alert centre and its actions, performance alerts, and remote administration. No Discord input can run anything on the server.

38 JUnit tests pass here. They run the webhook client against a local HTTP server, and the gateway client against a local WebSocket server that speaks Discord's protocol.

The client game test is written but has not been run. Delivery to real Discord is checked by hand, as listed in `mejta-discord/README.md`.
