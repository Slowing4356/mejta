# MejtaCore

MejtaCore (mod ID `mejtacore`) is the shared framework for Mejta addon mods. Every addon gets these from MejtaCore instead of building its own:

- A chest-style in-game menu, opened with `/mejtacore` or `/mejta`. Addons can add their own entries and pages to it.
- A command namespace. Each addon gets its own subcommands under `/mejtacore <addon id> …`.
- An addon registry, with dependency ordering and clear errors when something goes wrong.
- Typed, validated, persistent configuration: one JSON file per addon, with migrations.
- Logging and diagnostics that you can see in-game.
- **Since 1.2.1:** every notification (alerts, kicks, config changes) is also archived in `mejta-data/mejtacore/notifications.jsonl`, and config changes are published as notifications.
- **Since 1.2.0 (API version 3):** edit a **server's** settings in-game from the menu (**Configuration → Server Settings**) or with `/mejtaadmin config get/set/reset`. Every change is permission-checked, validated, saved and logged by the server.
- **Since 1.1.0 (API version 2), server features:** server commands under `/mejtaadmin <addon id> …`, permissions based on operator levels (configurable per node), a notification service that delivers to in-game staff chat and to optional provider mods, and size-limited JSON-lines record logs for each addon.

| | |
|---|---|
| Minecraft | **26.1.2** (Java Edition) |
| Mod loader | **Fabric Loader 0.19.5** |
| Fabric API | **0.155.3+26.1.2** (required at runtime) |
| Java | **25** |
| Build | Gradle 9.7.1 (wrapper), Fabric Loom `net.fabricmc.fabric-loom` 1.18.4 |
| Mappings | None: Minecraft 26.1 is unobfuscated, so the code uses Mojang's names |
| Third-party libraries | None. Gson and SLF4J already ship with Minecraft. |

---

## 1. Installing (players)

1. Install **Fabric Loader 0.19.5** (or newer) for Minecraft **26.1.2**.
2. Put these jars in `.minecraft/mods/`:
   - `fabric-api-0.155.3+26.1.2.jar` (or a newer 26.1.2 build) from Modrinth or CurseForge
   - `mejtacore-1.2.1.jar`
   - any Mejta addons
3. Start the game. In a world, type `/mejta` to open the menu.

### Who needs what

| | Dedicated server | Player's client |
|---|---|---|
| MejtaCore | Required if any Mejta addon runs on the server | Optional: needed for the `/mejta` menu, client commands, and **editing server settings in-game** |
| Fabric API | Required | Required if the client has MejtaCore |
| Server-side addons (e.g. MejtaAC) | Required | **Not needed.** Vanilla clients can join and play. |

A client without MejtaCore, or with a version older than 1.2.0, still joins normally; it just isn't offered Server Settings. In single-player, install everything on the client.

If an addon is installed without MejtaCore, or with an incompatible version, Fabric Loader refuses to start. It shows a clear "requires mod mejtacore" message instead of crashing.

Configuration files are stored in `.minecraft/config/mejta/` (on a dedicated server: `config/mejta/`): `mejtacore.json`, `mejtacore.permissions.json`, plus one `<addon id>.json` per addon. Deleting a file resets it to defaults. Removing an addon leaves the other files untouched.

Runtime data that addons record (for example anti-cheat violation history) is stored in `mejta-data/<addon id>/` next to the `config` folder. Each log has a size cap and a retention period.

On a **dedicated server**, install MejtaCore, Fabric API and the addons in the server's `mods/` folder. Players don't need any mods to be affected by server-side addons or to use `/mejtaadmin`. To change the server's settings from the in-game menu, a player needs MejtaCore 1.2.0+ on their client and the `mejtacore.config.edit` permission.

## 2. Commands

### 2.1 Client commands (`/mejtacore`, `/mejta`)

These commands are **client-side**. They need no operator permission and work in single-player and on any server, even one without MejtaCore installed. Every command also works with `/mejta` in place of `/mejtacore`.

| Command | What it does |
|---|---|
| `/mejtacore`, `/mejtacore gui` | Opens the main menu |
| `/mejtacore help` | Lists all commands, including addon subcommands |
| `/mejtacore addons` | Lists registered addons with version and status (and why a failed addon failed) |
| `/mejtacore config reload` | Re-reads every config file from disk. Invalid values fall back to defaults and are reported. |
| `/mejtacore about` | Shows the version, API version and addon count |
| `/mejtacore <addon> …` | Subcommands added by addons, e.g. `/mejtacore example status` |

### 2.2 Server commands (`/mejtaadmin`, since 1.1.0)

These run **on the server** (dedicated, or the integrated server in single-player) and act on the server's files and players. Players need no mods to use them. The root is deliberately not `/mejta`, so the server commands never collide with the client commands above.

| Command | Permission (default level) | What it does |
|---|---|---|
| `/mejtaadmin`, `/mejtaadmin help` | none | Lists the server commands you are allowed to use |
| `/mejtaadmin addons` | `mejtacore.admin` (GAMEMASTERS, 2) | Lists addons and their state |
| `/mejtaadmin config reload` | `mejtacore.admin` (GAMEMASTERS, 2) | Re-reads every config file on the server |
| `/mejtaadmin config get <config> [key]` | `mejtacore.config.edit` (ADMINS, 3) | Shows settings with their defaults, e.g. `config get mejtaac enforcement.mode` |
| `/mejtaadmin config set <config> <key> <value>` | `mejtacore.config.edit` (ADMINS, 3) | Changes a setting; validated, saved at once and logged |
| `/mejtaadmin config reset <config> <key>` | `mejtacore.config.edit` (ADMINS, 3) | Restores a setting's default |
| `/mejtaadmin <addon> …` | set by the addon | Server subcommands added by addons |

### 2.3 Permissions

Permissions use Minecraft's built-in operator levels: `ALL` (0, everyone), `MODERATORS` (1), `GAMEMASTERS` (2), `ADMINS` (3) and `OWNERS` (4). No permissions mod is needed.

Each permission node has a default level chosen by the addon that declares it. Server owners can change it in `config/mejta/mejtacore.permissions.json`, or in single-player under **Configuration → Permissions** in the menu:

```json
{
  "config_version": 1,
  "mejtacore.admin": "GAMEMASTERS",
  "mejtaac.alerts": "MODERATORS"
}
```

After editing the file, run `/mejtaadmin config reload`. A changed level takes effect immediately for permission checks. Command suggestions update when a player rejoins.

Editing the permission levels themselves remotely, from the menu or with `config set mejtacore.permissions …`, also needs `mejtacore.config.permissions` (OWNERS, 4). Without it, the permissions file isn't shown at all, so nobody can raise their own level.

## 3. The menu

The menu is a **client-side screen drawn with the vanilla chest texture**. It isn't a real inventory: nothing is sent to the server, and your inventory isn't touched.

- **MejtaCore Settings**: menu height (3–6 rows), click sound, debug logging, showing addon IDs in tooltips.
- **Installed Addons**: every registered addon (green = active, red = failed). Click one to see its details, its settings and its own entries. If anything went wrong, a **Problems** entry lists it.
- **Configuration**: every config file on your computer. Click one to edit it. Also has **Reload from Disk** and **Save Now**.
  - **Server Settings** (on a multiplayer server with MejtaCore 1.2.0+): the server's config files, including those of server-only addons such as MejtaAC. You need `mejtacore.config.edit` (operator level 3 by default); otherwise the page says **No permission**. Edits are sent to the server, which checks the permission again, validates the value with the addon's own rules, saves the file at once, and logs who changed what (`[config] Steve changed mejtaac: enforcement.mode = ENFORCE`). Invalid values are refused with a message. The page refreshes when anyone changes a setting. In single-player there is no Server Settings entry, because your own files *are* the server's.
- **About**: versions of MejtaCore, Minecraft, Fabric Loader and Fabric API.

The bottom row is navigation: **Back**, **Previous/Next page** (or scroll the mouse wheel), and **Close**. Press Esc to close.

How to edit a setting:

| Type | Left click | Right click | Shift | Middle click | Ctrl + click |
|---|---|---|---|---|---|
| Boolean | toggle | toggle | | | reset to default |
| Integer | +1 | −1 | ×10 | type a value | reset to default |
| Decimal | +0.1 | −0.1 | ×10 | type a value | reset to default |
| Enum | next | previous | | | reset to default |
| String | edit | edit | | | reset to default |

"Reset All to Defaults" needs a Shift-click to confirm. Changes are saved when the menu closes, and only if something actually changed.

---

## 4. Building from source

You need **JDK 25**. Point `JAVA_HOME` at it. This machine uses the JDK that ships with the Modrinth app:

```bash
export JAVA_HOME="/c/Users/master/AppData/Roaming/ModrinthApp/meta/java_versions/jdk-25.0.4"
```

Then, from this folder:

```bash
./gradlew build
```

On Windows `cmd`/PowerShell, use `gradlew.bat build`.

Outputs in `build/libs/`:

- `mejtacore-1.2.1.jar`: the mod. Install this one.
- `mejtacore-1.2.1-sources.jar`: sources, for IDEs.
- `mejtacore-1.2.1-javadoc.jar`: javadoc for the public API only.

Other useful tasks:

| Task | Purpose |
|---|---|
| `./gradlew runClient` | Starts a development client with MejtaCore loaded |
| `./gradlew publishToMavenLocal` | Publishes `com.mejta:mejtacore:1.2.1` to `~/.m2` so addon projects can compile against it |
| `./gradlew genSources` | Decompiles Minecraft so you can browse its sources in the IDE |

## 5. Project layout

```
main/
├── build.gradle, settings.gradle, gradle.properties   build setup (versions live in gradle.properties)
├── src/main/java/com/mejta/mejtacore/                  common code (client + dedicated server)
│   ├── api/            PUBLIC API: MejtaCoreAPI, MejtaAddon, AddonRegistrar
│   │   ├── addon/      AddonDefinition, AddonInfo, AddonContext, AddonState, AddonInitializer
│   │   └── config/     Setting, ConfigSpec, ConfigHandle, ConfigMigration, ConfigData
│   ├── core/           MejtaCore (main entrypoint), MejtaCoreServer, CoreSettings
│   ├── addons/         AddonRegistry (registration, dependency resolution, init)
│   ├── config/         ConfigManager, ConfigFile (JSON persistence, migration, recovery)
│   ├── logging/        MejtaLog, Diagnostics
│   └── util/           SafeFiles (atomic writes, backups)
├── src/client/java/com/mejta/mejtacore/                client-only code
│   ├── api/client/     PUBLIC API: MejtaCoreClientAPI, MenuEntry, MenuAction, MenuContext,
│   │                   ClientCommandProvider, MejtaClientAddon
│   ├── core/           MejtaCoreClient (client entrypoint)
│   ├── commands/       MejtaCommands (/mejtacore and /mejta)
│   ├── ui/             ChestMenuScreen, CoreMenus, ConfigMenus, TextInputScreen, ScreenOpener
│   └── addons/         ClientAddonExtensions (menu entries and commands per addon)
└── test-addon/         separate Gradle project: example addon + automated in-game tests
```

**Only `com.mejta.mejtacore.api` and `com.mejta.mejtacore.api.client` are public API.** Everything else is internal and may change without notice. The javadoc jar documents only the API packages.

### Lifecycle

1. **`main` phase**: Fabric runs every mod's `ModInitializer`. MejtaCore loads `mejtacore.json` and calls every `"mejtacore"` entrypoint. Addons register either there or by calling `MejtaCoreAPI.registerAddon(...)` from their own `onInitialize()`. **Load order doesn't matter.**
2. **`client` / `server` phase**: this starts after every `main` entrypoint has run. MejtaCore closes registration and resolves the addons:
   - It rejects duplicate IDs. The first registration wins, and the rejected one is reported.
   - It sorts addons so dependencies initialize first.
   - It disables any addon whose required dependency is missing or failed, or that is part of a dependency cycle.
   - It loads each addon's config, then runs its init callback. An exception there disables only that addon and the addons that require it.
3. On the client, MejtaCore then calls `"mejtacore-client"` entrypoints. The command tree is built each time you join a world or server.

---

## 6. Developer guide: writing an addon

The `test-addon/` folder is a complete, working addon (`mejtacore_example`). Copy it as a starting point.

### 6.1 Gradle

```groovy
// settings.gradle: same pluginManagement block as MejtaCore (Fabric maven + Gradle plugin portal)

// build.gradle
plugins {
	id 'net.fabricmc.fabric-loom' version "1.18.4"
}

repositories {
	mavenLocal { content { includeGroup "com.mejta" } }   // until MejtaCore is on a public Maven
}

dependencies {
	minecraft "com.mojang:minecraft:26.1.2"
	implementation "net.fabricmc:fabric-loader:0.19.5"
	implementation "net.fabricmc.fabric-api:fabric-api:0.155.3+26.1.2"
	implementation "com.mejta:mejtacore:1.2.1"   // compile against it + load it in dev runs
}
```

Run `./gradlew publishToMavenLocal` in MejtaCore first. Don't use Loom's `include` to bundle MejtaCore inside your addon: players install it once, as its own mod.

- **At compile time**, your addon depends on `com.mejta:mejtacore`.
- **At runtime**, players need `mejtacore-x.y.z.jar` and Fabric API in their mods folder.
- **Third-party libraries**: there are none.

### 6.2 fabric.mod.json

```json
{
  "schemaVersion": 1,
  "id": "myaddon",
  "version": "${version}",
  "name": "My Addon",
  "environment": "*",
  "entrypoints": {
    "mejtacore": [ "com.example.myaddon.MyAddon" ],
    "mejtacore-client": [ "com.example.myaddon.client.MyAddonClient" ]
  },
  "depends": {
    "fabricloader": ">=0.19.5",
    "minecraft": "~26.1.2",
    "fabric-api": "*",
    "mejtacore": ">=1.1.0 <2.0.0"
  }
}
```

The `depends` entry on `mejtacore` makes Fabric Loader stop with a readable error if MejtaCore is missing or outside the version range.

### 6.3 Registering the addon (common code)

```java
public final class MyAddon implements MejtaAddon {
	public enum Mode { NORMAL, FAST }

	public static final Setting<Boolean> ENABLED = Setting.bool("enabled", true).describedAs("Turns the addon on or off");
	public static final Setting<Integer> RADIUS  = Setting.integer("radius", 8, 1, 64).describedAs("Radius in blocks");
	public static final Setting<Double>  SPEED   = Setting.decimal("speed", 1.0, 0.1, 5.0);
	public static final Setting<String>  NAME    = Setting.string("name", "Steve").validatedBy(s -> !s.isBlank());
	public static final Setting<Mode>    MODE    = Setting.enumeration("mode", Mode.NORMAL);

	public static final ConfigSpec CONFIG = ConfigSpec.builder(1)
			.add(ENABLED, RADIUS, SPEED, NAME, MODE)
			.build();

	@Override
	public void registerAddons(AddonRegistrar registrar) {
		registrar.register(AddonDefinition.builder("myaddon")   // unique ID: [a-z][a-z0-9_-]{0,63}
				.name("My Addon")
				.version("1.0.0")
				.description("Does something useful")
				.icon("minecraft:diamond")                     // item ID used as menu icon
				.config(CONFIG)                                // -> config/mejta/myaddon.json
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

You can also skip the entrypoint and call `MejtaCoreAPI.registerAddon(definition)` from your `ModInitializer.onInitialize()`. Registering any later than that is rejected, and the rejection is reported.

Reserved IDs, which you can't use: `mejtacore`, `mejta`, `core`, `gui`, `help`, `addons`, `config`, `about`.

### 6.4 Using configuration

```java
ConfigHandle config = MejtaCoreAPI.getAddon("myaddon").flatMap(AddonInfo::config).orElseThrow();
int radius = config.get(MyAddon.RADIUS);
config.set(MyAddon.RADIUS, 16);         // throws IllegalArgumentException if invalid
config.trySet(MyAddon.RADIUS, 999);     // returns false instead (999 > max)
config.reset(MyAddon.RADIUS);
config.resetAll();
config.save();                          // writes only if something changed
config.addChangeListener(() -> { /* react to edits, resets and reloads */ });
```

When a config file is read, MejtaCore handles problems like this:

- **Missing file**: created with the defaults.
- **Missing or invalid value** (wrong type or out of range): replaced by the default and reported.
- **Corrupt JSON**: the file is renamed to `<id>.json.broken-<timestamp>` and a fresh file with the defaults is written.
- **Unknown keys**: kept. This means values from a newer version, or settings you removed, aren't lost.

**Migrations:** when you rename or reinterpret a setting, bump the version and add a migration step:

```java
ConfigSpec.builder(2)
	.add(...)
	.migration(1, data -> data.rename("range", "radius"))   // runs for files at version 1
	.build();
```

### 6.5 Menu entries and commands (client code)

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

		// Adds /mejtacore myaddon status and /mejta myaddon status
		MejtaCoreClientAPI.registerCommands("myaddon", root -> root
				.then(ClientCommands.literal("status").executes(ctx -> {
					ctx.getSource().sendFeedback(Component.literal("All good"));
					return 1;
				})));
	}
}
```

Entries appear in the main menu and on the addon's page under **Installed Addons**. `MenuContext` gives you `openPage`, `openScreen`, `refresh`, `back`, `close`, the clicked mouse button and modifier keys. A `null` element in a page's entry list leaves that slot empty. Pages with more entries than fit are split into pages automatically.

### 6.6 Server features (API version 2, MejtaCore 1.1.0+)

Nothing extra is needed for an addon's settings to be editable on servers: every config registered with MejtaCore automatically appears under Server Settings (MejtaCore 1.2.0+), and its validators run on the server. Enum settings are sent to clients as named options, so the addon does not have to be installed on the client. For options that are not a Java enum, use `Setting.choice("key", "default", List.of("a", "b"))` (API version 3); `setting.options()` lists the allowed names of any enum or choice setting.

Addons that use these must require `"mejtacore": ">=1.1.0 <2.0.0"`. Everything in this section is common code: it runs on dedicated servers and on the integrated server in single-player.

**Permissions.** Declare nodes on the addon definition. The node must start with your addon ID and a dot:

```java
public static final MejtaPermission ALERTS = MejtaPermission.of("myaddon.alerts", PermissionLevel.MODERATORS)
		.describedAs("See alerts in chat");

AddonDefinition.builder("myaddon")
		.permissions(ALERTS)
		...

MejtaCoreAPI.hasPermission(serverPlayer, ALERTS);      // or a CommandSourceStack
Commands.literal("x").requires(MejtaCoreAPI.requires(ALERTS));
```

**Server commands.** These are added under `/mejtaadmin <addon id>`. Gate every subcommand with a permission. The addon root itself is visible to everyone:

```java
MejtaCoreAPI.registerServerCommands("myaddon", (root, buildContext) -> root
		.then(Commands.literal("status")
				.requires(MejtaCoreAPI.requires(ALERTS))
				.executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("OK"), false); return 1; })));
```

**Notifications.** Publish events to a channel (`<addon id>.<name>`). Providers decide where they go. The built-in `ingame` provider shows a notification in chat to online players who have its `inGameAudience` permission. A player can mute a channel with `notifications().setMuted(uuid, channel, true)`; mutes are kept in memory only. Other mods, such as a future MejtaDiscord, register their own `NotificationProvider`. Publishers never depend on them, and a missing provider simply means the event isn't delivered there.

```java
MejtaCoreAPI.notifications().publish(Notification.builder("myaddon", "myaddon.alert")
		.severity(Severity.WARNING)
		.title("Steve did something")
		.message("details")
		.field("player", "Steve")              // structured fields for providers
		.inGameAudience(ALERTS)                // omit to skip in-game chat
		.build());
```

- `publish` never blocks. Delivery runs on one background thread with a queue of 1024 notifications. When the queue is full, new notifications are dropped and counted (`droppedCount()`). A provider that throws is logged at most once per minute and doesn't affect other providers.
- **Privacy:** a notification may be forwarded to external services by providers the server owner has configured. Never put passwords, tokens, IP addresses or other secrets in one. MejtaCore itself sends nothing over the network.
- The `in_game_notifications` setting in `mejtacore.json` turns the in-game provider off.
- **Since 1.2.1, every notification is also archived** by the built-in `file` provider in `mejta-data/mejtacore/notifications.jsonl`: one JSON object per line with time, source, channel, severity, title, message and fields. It rotates at 5 MiB and keeps 5 files for up to 30 days. This is the local log when no Discord addon is installed. Setting: `notifications.file_log` (on by default).
- **Since 1.2.1, config changes are published** on `mejtacore.config` (who, config, key, new value), to staff with `mejtacore.config.edit`, the file log and external providers. Values of keys containing `token`, `secret`, `password` or `webhook` are hidden, also in the server log.

| Channel | Published by | Content |
|---|---|---|
| `mejtacore.config` | MejtaCore | Config changes made in-game or with `/mejtaadmin config set/reset` |
| `mejtaac.alert` | MejtaAC | A player was flagged (rate-limited per player and check) |
| `mejtaac.enforcement` | MejtaAC | A player was kicked, or would have been in DRY_RUN |

**Record logs.** These are append-only JSON-lines files with limits on size and age, kept in `mejta-data/<addon id>/`:

```java
RecordLog log = MejtaCoreAPI.recordLog("myaddon", "events", new RecordLogOptions(5L << 20, 5, 30)); // 5 MiB x 5 files, 30 days
JsonObject record = new JsonObject();
record.addProperty("player", "Steve");
log.append(record);                                       // a "ts" field is added automatically
log.readRecent(20, r -> true).thenAccept(list -> ...);    // newest first, read on the I/O thread
```

- All disk I/O runs on one `MejtaCore-IO` thread, so `append` is safe to call from the server thread. It never throws: a full queue or a record that can't be written as JSON (NaN/Infinity) is dropped and counted.
- Files rotate by size (`events.jsonl` → `events.1.jsonl` → …). Disk usage is limited to roughly `maxFileBytes × maxFiles`. Rotated files older than `retentionDays` are deleted.
- Pending records are written to disk when the server stops and when the client closes.
- `MejtaCoreAPI.dataDirectory(addonId)` returns the folder if you need to store other files there.

## 7. Testing

`test-addon/` contains:

- **`mejtacore_example`**: the example addon. It uses every config type and a migration, adds a menu entry and an addon page, adds client and server commands, declares permissions, and registers a second addon via `MejtaCoreAPI.registerAddon` that depends on the first.
- **`mejtacore_example_test`** (dev only): deliberately broken registrations, plus a **Fabric client game test**. The broken registrations are a duplicate ID, a missing dependency, an init that throws, a dependent of a failed addon, a dependency cycle, and invalid, reserved and self-dependency IDs. The game test drives a real client:
  - it types commands into chat, clicks menu slots with the mouse and edits text fields;
  - it edits, corrupts and reloads config files;
  - it checks that values persist across restarts;
  - it repeats the key checks on a dedicated server as a non-operator player;
  - it checks permissions and per-node overrides, `/mejtaadmin` (as a non-op, with and without an override), notification delivery (including a provider that fails, muting and audience filtering), and record logs (ordering, filtering, rotation, size limits, unwritable records);
  - it edits the dedicated server's settings through the real menu as a non-op: **No permission** by default; with an override, clicks change the server's values and files (number and enum); the server rejects out-of-range values and values the addon's validator refuses; the permissions file stays hidden from non-owners; `/mejtaadmin config get/set` work; edits are refused again once the permission is revoked; there is no Server Settings entry in single-player.

The suite has 144 checks.

To run it:

```bash
cd main && ./gradlew publishToMavenLocal
cd test-addon && ./gradlew runClientGameTest
```

Every check prints `[MejtaTest] PASS …` or `[MejtaTest] FAIL …` to the log. Screenshots go to `test-addon/build/run/clientGameTest/screenshots/`. The run directory isn't cleared between runs, so a second run also verifies that `launch_count` survived the restart.

To see what players get when MejtaCore is missing:

```bash
cd test-addon && ./gradlew runClient -PwithoutMejtaCore
```

Fabric Loader stops with *"Mod 'MejtaCore Example Addon' (mejtacore_example) 1.0.0 requires any version between 1.0.0 (inclusive) and 2.0.0 (exclusive) of mejtacore, which is missing!"*

Note that Fabric's `TestInput` sends key and mouse events with no modifier bits. MejtaCore therefore also checks the live Shift/Ctrl key state, so Shift- and Ctrl-clicks behave the same under automation as they do in normal play.

## 8. Known limitations (v1.2.0)

- Server Settings needs MejtaCore 1.2.0+ on both the server and the client. Older clients and vanilla clients aren't offered it; they can use `/mejtaadmin config` with the permission.
- Editing permission is per server, not per addon: `mejtacore.config.edit` grants every config except the permissions file.
- Permissions are operator levels only. There's no per-player node assignment (for example via LuckPerms) yet.
- Notification mutes are kept in memory and reset on restart.
- Settings have no "experimental" flag or grouping yet. Addons mark experimental settings in their description, and dotted keys (`checks.x.enabled`) keep related settings together.
- There's no in-game keybind for the menu yet; use `/mejta`.
- The registry is populated during startup. Addons can't be registered or unregistered while the game is running.
- Menu text is English-only (no translation keys yet).
- `license` in `fabric.mod.json` is set to `All-Rights-Reserved`. Change it if you choose a different license.
