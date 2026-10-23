# MejtaCore

MejtaCore (mod ID `mejtacore`) is the shared platform of the Mejta mods. MejtaAC, MejtaUtils, MejtaDiscord and MejtaPerf are addons: they all require MejtaCore and get these from it:

- **Settings** with validation, one JSON file per addon in `config/mejta/`. Server staff can edit them in-game, even from a vanilla client (`/mejtaadmin config menu`).
- **Permissions** based on operator levels, adjustable per node. No permissions mod needed.
- **Commands**: server commands under `/mejtaadmin`, top-level addon commands such as `/invsee`, and client commands under `/mejta`.
- **Notifications** to staff chat, a local file log and provider mods such as MejtaDiscord.
- **Secret settings** (tokens, webhook URLs) that are never sent to clients, shown or logged.
- **A hash-chained audit log** of staff actions and config changes.
- **Storage**: size-limited record logs and a small JSON data store per addon.
- **Server menus** that work for players without mods: chests with one shared look (filler panes, pages, Back and Close buttons), which players with MejtaCore can switch to screens.
- **Presence** (so vanished staff stay hidden in every addon), a server-thread **scheduler** and **metrics**.
- **A shared economy API**, like Vault: an economy addon (MejtaEconomy) keeps the accounts, and shops, crates and auction houses pay through MejtaCore without depending on it.
- An optional **client menu** (`/mejta`) with a full **settings editor**, for players who install MejtaCore themselves. With it, players may also choose to see addon menus (homes, warps, leaderboards, staff panels) as screens instead of chests.

| | |
|---|---|
| Version | 1.9.0 (public API version 7) |
| Minecraft | 26.1.2 (Java Edition) |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | 0.155.3+26.1.2 or newer, required |
| Java | 25 |
| Third-party libraries | None |

## Installing

Put these in the server's `mods/` folder and restart:

- `mejtacore-1.9.0.jar`
- Fabric API for 26.1.2
- the Mejta addons you want

Settings files appear in `config/mejta/`. In single-player, put the same jars in `.minecraft/mods/`. If an addon is installed without MejtaCore, or with an incompatible version, Fabric Loader stops with a clear "requires mejtacore" message instead of crashing.

### Who needs what

| | Dedicated server | Player's client |
|---|---|---|
| MejtaCore | Required by every Mejta addon | Optional. Needed for the `/mejta` menu, the settings editor and client commands. |
| Fabric API | Required | Required only if the client has MejtaCore |
| Mejta addons (MejtaAC, MejtaUtils, ...) | Required | Not needed. Vanilla clients can join and play. |

### Files

| Path | What it holds |
|---|---|
| `config/mejta/mejtacore.json` | MejtaCore's own settings (see [Settings](#settings)) |
| `config/mejta/mejtacore.permissions.json` | Required operator level for each permission node |
| `config/mejta/<addon id>.json` | One file per addon |
| `config/mejta/secrets/<addon id>.json` | Values of secret settings. Readable only by the server's user on Linux and macOS. |
| `mejta-data/<addon id>/` | Runtime data addons record, such as anti-cheat history. Each log has a size cap and retention period. |
| `mejta-data/mejtacore/notifications.jsonl` | Every notification (alerts, kicks, config changes), one JSON object per line. Rotates at 5 MiB, keeps 5 files for up to 30 days. |
| `mejta-data/mejtacore/audit.jsonl` | The shared audit log. Rotates at 5 MiB, keeps 10 files for up to 365 days. |

A missing file is created with the defaults, so deleting one resets it. Missing or invalid values fall back to the default and are reported. A corrupt file is renamed to `<id>.json.broken-<timestamp>` and replaced with defaults. Unknown keys are kept.

If you edit a file by hand, run `/mejtaadmin config reload` before changing anything in-game. An in-game change rewrites the whole file and would replace your edits (a warning is logged when that happens).

## Commands

### Server commands (`/mejtaadmin`)

These run on the server and work from any client. They act on the server's files and players.

| Command | Permission | What it does |
|---|---|---|
| `/mejtaadmin` or `/mejtaadmin help` | none | Lists the server commands you may use |
| `/mejtaadmin addons` | `mejtacore.admin` | Lists addons and their state, with the reason if one failed |
| `/mejtaadmin config` | `mejtacore.config.edit` or `mejtacore.admin` | For a player who may edit settings: opens the settings editor if their client has MejtaCore 1.6+, otherwise the chest menu. Otherwise, and from the console, lists the config commands. |
| `/mejtaadmin config menu [config]` | `mejtacore.config.edit` | Opens the server's settings as a chest menu (see [below](#the-chest-menu-mejtaadmin-config-menu)) |
| `/mejtaadmin config get <config> [key]` | `mejtacore.config.edit` | Shows settings with their defaults, e.g. `config get mejtaac enforcement.mode` |
| `/mejtaadmin config set <config> <key> <value>` | `mejtacore.config.edit` | Changes a setting. It is validated, saved at once, logged and audited. |
| `/mejtaadmin config reset <config> <key>` | `mejtacore.config.edit` | Restores a setting's default |
| `/mejtaadmin config reload` | `mejtacore.admin` | Re-reads every config file from disk. Invalid values fall back to defaults and are reported. |
| `/mejtaadmin audit recent [count]` | `mejtacore.admin` | Shows the newest audit entries (default 10, at most 50) |
| `/mejtaadmin audit verify` | `mejtacore.admin` | Checks the audit log's hash chain and reports the first changed, removed or reordered line |
| `/mejtaadmin <addon> ...` | set by the addon | Server subcommands added by addons |
| `/<command>`, e.g. `/invsee` | set by the addon | Top-level commands added by addons. Each is also available as `/mejtaadmin <addon> <command>`. |

**Top-level command clashes.** If Minecraft or another mod already has a command with the same name, MejtaCore leaves it alone and logs a warning. The addon's command then works only as `/mejtaadmin <addon> <command>`. A mod that registers the same name *after* MejtaCore can't be detected, and its command replaces the addon's. To turn off chosen top-level commands, list them in `commands.disabled` in `mejtacore.json` (for example `vanish, freeze`), then run `/reload` or restart.

### Client commands (`/mejta`, `/mejtacore`)

These need MejtaCore on your own client. They run locally, need no operator level and work on any server. `/mejta` and `/mejtacore` are the same.

| Command | What it does |
|---|---|
| `/mejta` or `/mejta gui` | Opens the menu |
| `/mejta config` | Opens the settings editor |
| `/mejta help` | Lists the commands, including addon subcommands |
| `/mejta addons` | Lists registered addons, their version and status |
| `/mejta config reload` | Re-reads this computer's config files |
| `/mejta about` | Shows the MejtaCore version, API version and addon count |
| `/mejta <addon> ...` | Client subcommands added by addons |

## Permissions

Permissions use Minecraft's operator levels: `ALL` (0), `MODERATORS` (1), `GAMEMASTERS` (2), `ADMINS` (3) and `OWNERS` (4). Each node has a default level chosen by the mod that declares it.

| Node | Default | Allows |
|---|---|---|
| `mejtacore.admin` | `GAMEMASTERS` (2) | `/mejtaadmin addons`, `config reload` and `audit` |
| `mejtacore.config.edit` | `ADMINS` (3) | Viewing and changing the server's settings (menu or `config get/set/reset`), and seeing config-change notifications |
| `mejtacore.config.permissions` | `OWNERS` (4) | Also changing the permission levels themselves (needs `mejtacore.config.edit` too) |
| `mejtacore.config.secrets` | `OWNERS` (4) | Changing secret settings with `config set` (needs `mejtacore.config.edit` too) |

Addon nodes are listed in each addon's README. To change a level, run `/mejtaadmin config set mejtacore.permissions <node> <LEVEL>` (for example `mejtaac.alerts MODERATORS`), or edit `config/mejta/mejtacore.permissions.json` and run `/mejtaadmin config reload`. A new level applies to permission checks at once. Command suggestions update when the player rejoins. Without `mejtacore.config.permissions`, the permissions file is hidden from Server Settings, the chest menu and `config get/set`, so nobody can raise their own level.

## Settings

`config/mejta/mejtacore.json`:

| Key | Default | What it does |
|---|---|---|
| `in_game_notifications` | `true` | Show addon notifications (such as alerts) in chat to players with the required permission |
| `notifications.file_log` | `true` | Write every notification to `mejta-data/mejtacore/notifications.jsonl` |
| `commands.disabled` | empty | Top-level addon commands not to register, comma-separated. They stay available under `/mejtaadmin <addon>`. Takes effect after `/reload` or a restart. |
| `afk.auto_minutes` | `5` (0–120) | A player idle this long becomes AFK, for every addon that uses AFK status. `0` = never automatically. |
| `combat.tag_seconds` | `10` (0–300) | After hurting or being hurt by a player, both are in combat this long (addons refuse teleports, AFK protection and the like). `0` = off. |
| `debug_logging` | `false` | Log extra diagnostic information |
| `menu_sound` | `NORMAL` | Click sound in the client menu: `OFF`, `QUIET` or `NORMAL` |
| `menus_as_screens` | `false` | Show addon menus from the server (homes, warps, the auction house, staff panels) as MejtaCore screens instead of the chests everyone else sees |
| `show_addon_ids` | `false` | Show addon IDs and sources in client menu tooltips |

The last three only matter on a client with MejtaCore.

## Editing server settings in-game

There are three ways. All need `mejtacore.config.edit` and take the same path on the server: the permission is checked again, the value is validated with the addon's own rules, the file is saved at once, and the change is logged (`[config] Steve changed mejtaac: enforcement.mode = ENFORCE`), written to the audit log and published on the `mejtacore.config` notification channel. Values of secret settings never appear in any of these.

1. The **settings editor**, for players with MejtaCore on their client: `/mejta config [config]`, **Settings** in the `/mejta` menu, or `/mejtaadmin config`.
2. `/mejtaadmin config menu`: a chest menu that works on any client.
3. `/mejtaadmin config get/set/reset` in chat or the console.

### The chest menu (`/mejtaadmin config menu`)

The server runs this menu, so it works without any client mod. Nothing in it can be moved or taken. `/mejtaadmin config` with no arguments opens it too, and `/mejtaadmin config menu <config>` opens one config directly.

- **First page:** the configs you may edit. The permissions file is listed only if you also have `mejtacore.config.permissions`.
- **A config's groups:** a config with more than 18 settings opens on its groups first. Settings are grouped by the part of their key before the first dot, so `homes.max` and `homes.cooldown_seconds` are in **Homes**; keys without a dot are in **General**. Each group icon shows how many settings it holds and how many differ from the default. The client menu (`/mejta`) groups the same way.
- **A config's (or group's) page:** one icon per setting, 45 per page. The icon shows the type: dye (on/off), repeater (whole number), clock (decimal), hopper (choice), name tag (text) and tripwire hook (secret). The tooltip shows the key, description, current value, default, range or options, and an **Experimental** marker where it applies.
- **Bottom row:** Back, Previous/Next page, the result of your last change, and Close.

| Type | Left click | Right click | Shift + click | Drop key (Q) |
|---|---|---|---|---|
| On/off | toggle | toggle | | reset to default |
| Whole number | +1 | −1 | ±10 | reset to default |
| Decimal | + step | − step | ±10 steps | reset to default |
| Choice | next | previous | | reset to default |
| Text | closes the menu and puts a clickable `/mejtaadmin config set <config> <key> ` in chat | same | | reset to default |
| Secret | not editable: shows `(set)` or `(not set)` and the console command to use | | | |

The decimal step is a power of ten that splits the range into 20 to 200 steps (0.01 for 0 to 1); without a range it is 0.1. Numbers stop at the range limits, and choices the addon's rules refuse are skipped. Each result appears in the action bar and the bottom row, and refusals also in chat. The menu refreshes on its own and re-checks the permission on every click and refresh; if the permission is gone, it closes.

### The settings editor (`/mejta config`)

With MejtaCore on your client, every setting can be edited in one screen. Open it with `/mejta config` (or `/mejta config <config>`, for example `/mejta config mejtaqol`), **Settings** in the `/mejta` menu or on an addon's page there, or `/mejtaadmin config` on a server.

- **Sidebar:** every config. On a multiplayer server with MejtaCore it has two parts: **Server** (the server's configs; edits go to the server, which checks and saves them at once) and **This Computer** (your own files, which do **not** change the server). Without `mejtacore.config.edit`, the Server part says **No permission**; if the server's MejtaCore uses a different settings protocol, it says so. In single-player (and on the computer hosting a LAN world) there is no Server part, because your files *are* the server's.
- **Settings list:** one row per setting, under category headers (the part of the key before the first dot: `homes.max` is in **Homes**). Each row shows the name, the description and a widget that fits the type:

| Type | Widget |
|---|---|
| On/off | an ON/OFF button |
| Choice | a button: click for the next option, Shift-click for the previous |
| Whole number, decimal | a slider over the range (in the setting's steps) plus a field to type a value; a field only when there is no useful range |
| Text | a text field |
| Secret | `(set)` or `(not set)`; **Change...** opens a box for a new value, which the server may still refuse (see below) |

- Typed values are checked as you type. An invalid one turns red and the row says why ("Must be from 1 to 64", "not a number"). A valid one is taken when you press Enter, leave the field or stop typing for half a second. Leaving a field with an invalid value puts the current value back.
- A yellow bar and a yellow name mark settings that differ from their default; the **↺** button resets one. Hover over a name for the full description, the default, the range or options and the key. Experimental settings of this computer are marked **EXP**.
- **Search** (top) filters by name, key, description and category, across all configs: the sidebar shows how many settings match in each. **Show: Changed** lists only the settings that differ from their default.
- **Bottom row:** **Reload Files** (re-reads this computer's files), **Reset All** (click twice; secret settings are kept), **Undo Changes** (puts back every setting you changed since the screen opened, on the server too) and **Done**. Results of your changes, including the server's answer, show at the bottom left; refusals by the server are also written to chat.
- Changes to this computer's files are saved when the screen closes (Done or Escape). Changes to the server's settings are saved by the server at once.

### The client menu (`/mejta`)

`/mejta` opens the MejtaCore menu, in the same style as the settings editor. It is a client screen, not a real inventory: your items are not touched. A sidebar leads to:

- **Overview:** **Settings** (the settings editor; on a server you may edit, the server's settings first), **This Computer** (only on a server: your own MejtaCore settings), the **Server Menus** switch (screens or chests, see below), a card per addon and the **Addon Actions** that addons added.
- **One page per addon** (red in the sidebar if it failed): its version, whether it runs and why not, what it needs, its **Settings** and its own actions. On a server that has the same addon, **Settings** opens the server's copy and **This Computer's Copy** yours.
- **Problems:** rejected registrations, config errors and failed addons.
- **About:** the versions of MejtaCore, Minecraft, Fabric Loader and Fabric API, and the commands.

Hover over a card for its full description; the mouse wheel scrolls.

### Addon menus as screens

Addon menus run by the server (homes, warps, the sidebar picker, leaderboards, vanish settings, staff status panels, confirmations) are chests, for every player: the Mejta mods are server-side, so everyone sees the same menus. With MejtaCore 1.7+ on your client and on the server you can choose to open them as a MejtaCore screen instead: one card per icon, in the chest's order, with the icon's name and first lore line on the card and the rest on hover. Left, right, shift and middle clicks reach the server just as they would from the chest, and the server still runs everything (permission checks, refreshes, closing). Menus that hold real items (`/invsee`, `/ec`, corpses, `/trash`, portable workstations) stay normal inventories.

To try screens, switch **Server Menus** on the `/mejta` overview, or set `menus_as_screens` to `true` in **This Computer**'s settings. It is off by default since 1.9.0 (the setting was called `server_menu_screens` and on by default before). `/mejtaadmin config menu` is always a chest.

### Secret settings

Some addon settings, such as MejtaDiscord's webhook URLs, are secret. They are stored in `config/mejta/secrets/<id>.json` and never sent to clients. Menus, `config get`, logs, notifications and audit entries show only `(set)` or `(not set)`.

On a server, secrets can't be changed from any menu. Use `/mejtaadmin config set`, which also needs `mejtacore.config.secrets`; the reply hides the value and is not broadcast to other operators. **Use the server console when you can:** anything typed in chat can end up in your own client's logs. You can also edit the secrets file and run `/mejtaadmin config reload`.

## Writing an addon

The `test-addon/` folder is a complete working addon (mod `mejtacore_example`, addon ID `example`) that uses most of the API. Copy it as a starting point. Only the packages `com.mejta.mejtacore.api` and `com.mejta.mejtacore.api.client` are public API; everything else is internal and may change. The javadoc jar documents the API.

### Gradle

Publish MejtaCore to Maven Local first (`./gradlew publishToMavenLocal` in `mejta-core/`), then:

```groovy
plugins { id 'net.fabricmc.fabric-loom' version "1.18.4" }
repositories { mavenLocal { content { includeGroup "com.mejta" } } }
dependencies {
	minecraft "com.mojang:minecraft:26.1.2"
	implementation "net.fabricmc:fabric-loader:0.19.5"
	implementation "net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2"
	implementation "com.mejta:mejtacore:1.8.0"
}
```

Don't bundle MejtaCore into your jar with `include`. Players install it once, as its own mod.

### fabric.mod.json

Besides the usual fields (`"environment": "*"`, `fabricloader`, `minecraft` and `java` dependencies):

```json
"entrypoints": {
  "mejtacore": [ "com.example.myaddon.MyAddon" ],
  "mejtacore-client": [ "com.example.myaddon.client.MyAddonClient" ]
},
"depends": {
  "fabric-api": "*",
  "mejtacore": ">=1.3.0 <2.0.0"
}
```

`>=1.3.0` is the first version with API 4. The `mejtacore-client` entrypoint is optional. With the `depends` entry, Fabric Loader refuses to start with a readable error if MejtaCore is missing or out of range.

### Registering the addon and its settings

```java
public final class MyAddon implements MejtaAddon {
	public enum Mode { NORMAL, FAST }

	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true).describedAs("Turns the addon on or off");
	public static final Setting<Integer> RADIUS  = Setting.integer("radius", 8, 1, 64).describedAs("Radius in blocks");
	public static final Setting<Double>  SPEED   = Setting.decimal("speed", 1.0, 0.1, 5.0);
	public static final Setting<String>  NAME    = Setting.string("name", "Steve").validatedBy(s -> !s.isBlank());
	public static final Setting<Mode>    MODE    = Setting.enumeration("mode", Mode.NORMAL);
	public static final Setting<String>  STYLE   = Setting.choice("style", "plain", List.of("plain", "fancy"));
	public static final Setting<String>  TOKEN   = Setting.string("bot.token", "").secret();
	public static final Setting<Boolean> PREVIEW = Setting.bool("preview", false).experimental();

	public static final MejtaPermission ALERTS = MejtaPermission.of("myaddon.alerts", PermissionLevel.MODERATORS)
			.describedAs("See alerts in chat");

	public static final ConfigSpec CONFIG = ConfigSpec.builder(2)
			.add(ENABLED, RADIUS, SPEED, NAME, MODE, STYLE, TOKEN, PREVIEW)
			.migration(1, data -> data.rename("range", "radius"))   // runs for files at version 1
			.build();

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder("myaddon")   // [a-z][a-z0-9_-]{0,63}
				.name("My Addon")
				.version("1.0.0")
				.description("Does something useful")
				.icon("minecraft:diamond")                     // item shown in menus
				.config(CONFIG)                                // config/mejta/myaddon.json
				.permissions(ALERTS)                           // nodes must start with "myaddon."
				.dependsOn("otheraddon")                       // optional: required Mejta addons
				.optionallyDependsOn("niceaddon")              // optional: initialize after it if present
				.onInitialize(ctx -> {
					ConfigHandle config = ctx.config().orElseThrow();
					ctx.logger().info("Radius is {}", config.get(RADIUS));
				})
				.build());
	}
}
```

- Instead of the entrypoint you can call `MejtaCoreAPI.registerAddon(definition)` from your own `ModInitializer.onInitialize()`. Load order doesn't matter. Later registrations are rejected.
- MejtaCore sorts addons so dependencies initialize first. An addon is disabled, with the reason shown in `addons`, if its ID is a duplicate, a required dependency is missing or failed, it is part of a cycle, or its init callback throws. Only that addon and the addons that require it are affected.
- Reserved IDs: `mejtacore`, `mejta`, `core`, `gui`, `help`, `addons`, `config`, `about`.
- Every registered config appears in Server Settings, `/mejtaadmin config` and the chest menu automatically, and its validators run on the server. Enum and choice settings are sent as named options, so the addon need not be installed on the client.
- Only string settings can be `.secret()`. String settings whose key contains `token`, `secret`, `password` or `webhook` are masked the same way even without it, but are stored in the normal file.

Using a config outside the init callback:

```java
ConfigHandle config = MejtaCoreAPI.getAddon("myaddon").flatMap(AddonInfo::config).orElseThrow();
config.set(MyAddon.RADIUS, 16);                    // throws IllegalArgumentException if invalid
boolean ok = config.trySet(MyAddon.RADIUS, 999);   // false instead (999 > max)
config.save();                                     // writes only if something changed
config.addChangeListener(() -> { /* react to edits, resets and reloads */ });
```

### Client menu entries and commands

```java
public final class MyAddonClient implements MejtaClientAddon {
	@Override
	public void initializeClient() {
		MejtaCoreClientAPI.registerMenuEntry("myaddon", MenuEntry.builder("status")
				.icon(() -> new ItemStack(Items.COMPASS))
				.title(Component.literal("My Status"))
				.description(Component.literal("Click me").withStyle(ChatFormatting.GRAY))
				.onClick(ctx -> ctx.openPage(Component.literal("My Page"), () -> List.of(
						MenuEntry.builder("hello").icon(() -> new ItemStack(Items.CAKE))
								.title(Component.literal("Say hello"))
								.onClick(inner -> inner.player().sendSystemMessage(Component.literal("Hello!")))
								.build())))
				.build());

		// /mejta myaddon status and /mejtacore myaddon status
		MejtaCoreClientAPI.registerCommands("myaddon", root -> root
				.then(ClientCommands.literal("status").executes(ctx -> {
					ctx.getSource().sendFeedback(Component.literal("All good"));
					return 1;
				})));
	}
}
```

Entries appear in the main menu and on the addon's page. `MenuContext` offers `openPage`, `openScreen`, `refresh`, `back`, `close`, the mouse button and modifier keys. A `null` entry leaves a slot empty, and long lists are split into pages.

### Server commands and permissions

```java
// /mejtaadmin myaddon status. Gate every subcommand: the addon root is visible to everyone.
MejtaCoreAPI.registerServerCommands("myaddon", (root, buildContext) -> root
		.then(Commands.literal("status")
				.requires(MejtaCoreAPI.requires(MyAddon.ALERTS))
				.executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("OK"), false); return 1; })));

// /mystatus, also available as /mejtaadmin myaddon mystatus
MejtaCoreAPI.registerRootCommand("myaddon", "mystatus", (root, buildContext) -> root
		.requires(MejtaCoreAPI.requires(MyAddon.ALERTS))
		.executes(ctx -> 1));

MejtaCoreAPI.hasPermission(serverPlayer, MyAddon.ALERTS);          // or a CommandSourceStack
MejtaCoreAPI.hasPermission(server, nameAndId, MyAddon.ALERTS);     // a player who may be offline
```

Register commands during initialization; the init callback is a good place. Commands of a failed addon are left out.

### Notifications

```java
MejtaCoreAPI.notifications().publish(Notification.builder("myaddon", "myaddon.alert")
		.severity(Severity.WARNING)
		.title("Steve did something")
		.message("details")
		.field("player", "Steve")       // structured fields for providers
		.inGameAudience(MyAddon.ALERTS) // omit to keep it out of in-game chat
		.build());
```

- Channels look like `<addon id>.<name>`. Providers decide where each channel goes. Built in are `ingame` (staff chat) and `file` (`notifications.jsonl`). Other mods, such as MejtaDiscord, add their own with `registerProvider`.
- `publish` never blocks. Each provider has its own thread and a queue of 1024. When a queue is full, the notification is dropped for that provider and counted. A provider that throws doesn't affect the others.
- `setMuted(uuid, channel, true)` hides a channel from one player's in-game chat. Mutes survive restarts.
- **Never put passwords, tokens, IP addresses or other secrets in a notification.** Providers may forward it to external services.

### Storage

**Record logs** are append-only JSON-lines files in `mejta-data/<addon id>/`, limited by size and age:

```java
RecordLog log = MejtaCoreAPI.recordLog("myaddon", "events", new RecordLogOptions(5L << 20, 5, 30)); // 5 MiB x 5 files, 30 days
log.append(jsonObject);                                 // adds a "ts" field; never blocks or throws
log.readRecent(20, r -> true).thenAccept(list -> ...);  // newest first
```

**The data store** keeps small JSON documents by key, one file each, in `mejta-data/<addon id>/store/`:

```java
DataStore store = MejtaCoreAPI.dataStore("myaddon");
store.write(DataStore.playerKey("frozen", uuid), doc);  // queued on the I/O thread
store.read(DataStore.playerKey("frozen", uuid));        // sees queued writes at once
store.writeNow("sessions/" + id, doc);                  // synchronous and flushed to disk; throws IOException
store.keys("frozen");                                   // keys in a folder
```

Writes are atomic and keep three backups (`.bak1` to `.bak3`). A corrupt file is renamed `.broken-<time>` and the newest readable backup is used. Keys are validated and can never reach a file outside the addon's folder; use `playerKey` rather than raw player input. `writeDurable` returns a future that completes once the document is on disk.

### Audit log

```java
MejtaCoreAPI.audit().record(AuditEntry.builder("myaddon", "inventory.edit")
		.actor(MejtaCoreAPI.actor(source)).target(uuid, name).outcome(AuditOutcome.SUCCESS)
		.detail("slot", 12).build(), MyAddon.ALERTS);  // second argument optional: also show it in chat to that permission
```

Each line holds the time, actor, action, target, outcome and details, plus the SHA-256 of the previous line and of itself, so `/mejtaadmin audit verify` can spot edits. It cannot stop someone with file access from deleting the whole log. Every entry is also published on `<addon>.audit`.

### Server menus

Menus run by the server. Every player sees a chest; players with MejtaCore 1.7+ can choose to see the same menu as a screen. Your code is the same either way:

```java
ServerMenu.builder(Component.literal("Status"), 3)
		.button(13, () -> MenuIcons.of(Items.APPLE, Component.literal("Health: " + target.getHealth())),
				click -> { if (click.type().isShift()) { /* ... */ } click.menu().refresh(); })
		.refreshEvery(10)
		.validWhile(p -> MejtaCoreAPI.hasPermission(p, MyAddon.ALERTS))
		.open(player);

ServerMenus.confirm(player, title, List.of(question), () -> doIt());   // check permissions again inside
```

No click type moves an item, in the menu or the player's inventory below it. Handlers run on the server thread. `validWhile` is checked on every click and refresh, and the menu closes once it fails. `onClose` runs once however the menu ends. As a screen, icons added with `icon(...)` are shown as information (not clickable), and an icon's name and lore are its card's title and description, so keep the first lore line short and useful.

### Presence, scheduler and metrics

- **Presence:** a vanish addon registers a `VisibilityProvider` with `MejtaCoreAPI.presence().registerVisibilityProvider(...)`. Every addon that shows or announces players asks `presence()`: `isHidden(uuid)`, `canSee(viewer, uuid)`, `publiclyOnline(server)`, and `addListener(...)` for public joins and leaves (a staff member's fake quit is reported with `real = false`).
- **Scheduler:** `MejtaCoreAPI.scheduler().runLater(20, task)` and `runEvery(20, task)` run on the server thread at the end of a tick. Both return a task you can `cancel()`.
**Shared look (API version 7, MejtaCore 1.9.0).** Give your menus the same layout as every Mejta menu. `PagedMenu` lists any number of entries over pages, with the standard bottom bar (Back, previous page, info and page number, next page, Close); pages turn in place:

```java
PagedMenu.<Home>builder(Component.literal("Homes"), () -> homesOf(player))
    .entry(home -> MenuIcons.of(Items.RED_BED, Component.literal(home.name())),
           (home, click) -> teleport(click.player(), home))
    .empty(() -> MenuIcons.of(Items.PAPER, Component.literal("No homes yet")))
    .back("menu", MyMenus::main)              // optional
    .barButton(6, () -> icon, click -> ...)   // optional, bar columns 1, 2, 6 or 7
    .open(player);
```

For fixed menus, `ServerMenu.Builder` has `backButton(slot, "where", player -> ...)`, `closeButton(slot)` and `fillEmpty()` (gray panes in every unused slot, left out on screens), and `MenuIcons` has `filler()`, `back(...)`, `close()` and `page(...)`.

- **Player status (API version 5):** `MejtaCoreAPI.status()` says who is AFK and who is in combat, so every addon agrees.
  - **AFK:** MejtaCore notices activity itself: looking around, walking (not being carried), chatting and interacting. After `afk.auto_minutes` idle, a player is AFK. Ask with `isAfk(uuid)` and `idleMillis(uuid)`. Set or clear it on request with `setAfk(player, boolean)` (an `/afk` command), report other activity with `markActive(player)`, and listen with `addAfkListener(...)` (cause `IDLE`, `ACTIVITY` or `REQUEST`).
  - **Combat:** a PvP hit (also with projectiles) puts both players in combat for `combat.tag_seconds`; death ends it. Ask with `inCombat(uuid)` and `combatRemainingMillis(uuid)`.
- **Economy (API version 6, MejtaCore 1.8.0):** `MejtaCoreAPI.economy()` is one economy for every addon, like Vault. An economy addon registers the single `EconomyProvider`; everyone else calls `EconomyService` and declares `"mejtacore": ">=1.8.0 <2.0.0"`.
  - Check `isAvailable()` first and hide money features without an economy.
  - Amounts are `long`s in minor units (`1050` is `10.50` with two decimals). `parse(text)` reads player input (`12.5`, `2k`), `format(amount)` and `currencyName(amount)` print it.
  - `deposit`, `withdraw` and the all-or-nothing `transfer(from, to, amount, source, reason)` return an `EconomyResult` with a status, the new balance and a message for players; nothing changes unless it succeeded. Deposits work for offline players. `source` is your addon ID, `reason` a short text for the economy's transaction log.
  - Also `balance`, `has`, `hasAccount`, `ensureAccount`, `top(n)` and `addListener(...)` for every change.
- **Metrics:** `metrics().timer(...)`, `counter(...)` and `gauge(...)`, read by tools such as MejtaPerf. Time a hot path with `long t = Metrics.Timer.start(); ... TIMER.stop(t);`. Recording takes no locks. Gauges are read on another thread, so keep them thread-safe. Names use lowercase letters, digits, `.`, `_` and `-`, and must never contain player names.

## Building and testing

You need JDK 25 (`JAVA_HOME` pointing at it). From `mejta-core/`:

```bash
./gradlew build                 # compiles and runs the unit tests
./gradlew publishToMavenLocal   # com.mejta:mejtacore:1.8.0, for addon projects
./gradlew runClient             # a development client with MejtaCore
```

`build/libs/` then holds `mejtacore-1.9.0.jar` (install this one), plus sources and javadoc jars.

**Unit tests** (`./gradlew test`) cover logic that needs no running game: secret storage and masking, the data store (ordering, durability, corrupt files, key validation), the audit hash chain, metrics, the config edit path and menu steps.

**Client game tests** live in `test-addon/`. After `publishToMavenLocal`, run `cd test-addon && ./gradlew runClientGameTest`. A real client types commands, clicks menu cards, slots and settings and edits files, in single-player and as a non-operator on a dedicated server. It covers addon registration and its failure cases (duplicate IDs, missing dependencies, cycles, failing init, invalid and reserved IDs), config persistence and recovery, permissions and overrides, `/mejtaadmin`, Server Settings and the chest config menu, notifications, record logs, secrets, top-level commands, the data store, the audit log, server menus (as a chest and as a screen), the main menu, presence and the scheduler. Each check logs `[MejtaTest] PASS` or `FAIL`, followed by a summary line. The run folder is kept between runs, so a second run also checks values that must survive a restart.

To see the error players get without MejtaCore: `cd test-addon && ./gradlew runClient -PwithoutMejtaCore`.

## Known limitations

- Permissions are operator levels only. There is no per-player node assignment (for example through LuckPerms).
- `mejtacore.config.edit` covers every config except the permissions file. There is no per-addon edit permission.
- The settings editor shows the server's settings only if the server has MejtaCore 1.2 or newer, and doesn't show the Experimental marker for them (the chest menu and `config get` do). With a client older than 1.6.0, `/mejtaadmin config` opens the chest menu.
- An in-game change rewrites the whole config file, so run `config reload` after editing a file by hand.
- A mod that registers a command name *after* MejtaCore silently replaces an addon's top-level command.
- Addons can't be registered or removed while the game is running.
- There is no keybind for the client menu (use `/mejta`), and menu text is English only.
- Server menus show as screens only when the server has MejtaCore 1.7+; older servers keep sending chests.
