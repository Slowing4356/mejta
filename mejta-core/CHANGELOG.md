# MejtaCore changelog

## 1.6.0 (API version 5)

The public API is unchanged, so every addon keeps working.

- **A real settings editor** replaces the chest-style settings pages of the client menu (`/mejta`). One screen lists every config in a sidebar (the server's and this computer's, clearly separated) and the selected config's settings under category headers, each with a widget that fits its type: an ON/OFF button, an option button, a slider plus a field for numbers, a text field. It adds:
  - checking as you type, with the reason in the row ("Must be from 1 to 64");
  - a reset button per setting and a marker on every setting that differs from its default;
  - search across all configs, and a filter for changed settings;
  - **Undo Changes** for everything changed since the screen opened, **Reset All** (click twice) and **Reload Files**;
  - the full description, default, range, options and key on hover.
- **Opening it:** `/mejta config` (it used to print the help), **Configuration**, **MejtaCore Settings** and an addon's **Settings** in the `/mejta` menu, and `/mejtaadmin config` on a server: that now opens the editor for players with MejtaCore 1.6+ on their client (on the server's MejtaCore settings) and the chest menu for everyone else. `/mejtaadmin config menu` still always opens the chest menu, which works without client mods.
- New network message `mejtacore:open_settings` (server to client), sent only to clients that have it. The settings protocol stays at 1, so 1.6.0 clients and servers work with 1.2+ on the other side.

## 1.5.0 (API version 5)

Addons built for API version 4 keep working.

- **Player status** (`MejtaCoreAPI.status()`): who is AFK and who is in combat, tracked once by MejtaCore so every addon agrees.
  - AFK moved here from MejtaQoL: activity (looking around, walking, chatting, interacting) and the idle timer. Addons set or clear AFK on request and listen to changes.
  - A PvP hit puts both players in combat; death ends it.
  - New settings `afk.auto_minutes` (5) and `combat.tag_seconds` (10).
- **Lighter notifications:** a player's notification mutes are read only for players allowed to see a notification, and forgotten when they leave.
- **Grouped settings menus:** a config with more than 18 settings now opens on groups, by the part of the key before the first dot. For example, MejtaQoL shows Teleport, Tpa, Homes, Warps, Afk and so on, instead of 49 settings at once. Both `/mejtaadmin config menu` and the client menu (`/mejta`) group this way.

## 1.4.0 (API version 4)

A bug-fix release for editing settings in-game, plus a config menu for players without mods. The public API is unchanged (still version 4), so addons built for 1.3.0 keep working.

### Fixed: "edits in the in-game menu don't stick"
- **Your own copy instead of the server's.** With the same mods installed on the client as on the server (for example MejtaAC), **Configuration** listed the client's own copies of those configs right below Server Settings. A click there edited a file on your computer, not the server. Now, on a server:
  - local files are labelled "(this computer)", and the page starts with a note that leads to Server Settings;
  - a config the server also offers opens the **server's** copy; Shift-click opens your own, labelled as such;
  - **MejtaCore Settings** carries the same note.
- **Refusals were easy to miss.** The server's "no" (missing permission, a value the addon's rules reject, a secret setting, the rate limit) appeared only in the action bar, hidden behind the menu. Now every result is shown in a line under the menu, and refusals are also written to chat. The same goes for clicks the client itself rejects (a refused value, already at a limit, no other allowed option), which used to do nothing.
- **Quick clicks were lost with network latency.** A list of values that the server had sent before it handled your click could arrive afterwards. The setting then jumped back, and the next click was computed from the old value, re-sending what the server already had. The client now keeps the values it sent until the server has answered every pending edit. Unanswered edits expire after 5 seconds.
- **"Reset All" on a Server Settings page reset only part of a large config.** It sent one edit per setting, and the server's limit of 20 edits a second refused the rest. It now sends a single reset-all edit. As before, the server keeps secret settings.
- **Edits that were never sent looked applied.** If the server no longer accepted edits, the client dropped the change silently but showed the new value. It now says so and keeps the old value.
- **A different settings protocol** on the server only logged a warning, and Server Settings said "Loading…" forever. It now says that the versions differ and points to `/mejtaadmin config menu`.
- **An open settings page could show stale values.** After the edit permission was revoked and granted again, the page stayed tied to an old copy of the config. It now looks the config up each time.
- **A change that couldn't be saved was reported as done.** If the config file couldn't be written, the change was in effect only until the next restart, but menus and commands still said it was done. It is now reported as a failure ("could NOT be saved … lost on restart"), logged as a warning, published with severity WARNING and audited as FAILED.
- **Hand edits overwritten without a word.** A save from the game rewrites the whole file, replacing edits made to it by hand since it was loaded. That still happens, but it is now logged as a warning that says to run `config reload` first.

### Added
- **`/mejtaadmin config menu [config]`**: the server's settings as a chest menu run by the server, so it works without any client mod. `/mejtaadmin config` with no arguments opens it for players who may edit settings.
  - **What you see:** the configs you may edit (the same permission rules as Server Settings), then one page per config, paged. Each icon shows the setting's type, and the tooltip shows its key, description, current value, default, range or options, and an Experimental marker.
  - **Editing:** click toggles a boolean; left/right cycles an enum or choice; ±1 step changes a number, ±10 with Shift, clamped to the range. The decimal step fits the range. The drop key resets to the default. A text setting closes the menu and puts a clickable `/mejtaadmin config set <config> <key> ` in chat.
  - **Secrets:** they show `(set)` or `(not set)` and can't be edited in the menu.
  - **Safety:** every change takes the same server path as the commands and Server Settings: permission check, validation, save, audit, notification. The permission is re-checked on every click and refresh, and the menu redraws when values change.

### Notes
- Addons and the test addon must compile against `mejtacore_version=1.4.0` (or keep 1.3.0 from a repository that still has it). Their `fabric.mod.json` range `>=1.3.0 <2.0.0` already accepts 1.4.0.
- The client and the dedicated test server share one JVM in the game test, which hid the first bug. The new checks test what the menus open, label and report, and the logic has unit tests (44 in all).

## 1.3.0 (API version 4)

First public release of the Mejta platform. Addons built for API 2 or 3 keep working unchanged.

### Added
- **Secret settings** (`Setting#secret()`). Values live in `config/mejta/secrets/<id>.json`, are never sent to clients, and are shown only as `(set)` or `(not set)`. Only `/mejtaadmin config set` with `mejtacore.config.secrets` can change them.
- **Experimental settings** (`Setting#experimental()`), marked in menus and in `config get`.
- **Top-level addon commands** (`registerRootCommand`). Each is mirrored under `/mejtaadmin <addon>`, a name already taken is detected, and owners can turn names off with `commands.disabled`.
- **JSON data store** per addon (`dataStore`). It writes atomically, keeps three backups, recovers corrupt files, and offers durable `writeNow`/`writeDurable` for write-ahead records.
- **Audit log** (`audit()`): hash-chained lines in `mejta-data/mejtacore/audit.jsonl`, checked with `/mejtaadmin audit verify`, and published on `<addon>.audit`.
- **Presence** (`presence()`), so vanish-aware addons agree on who is publicly online.
- **Server-side menus** (`ServerMenu`, `ServerMenus.confirm`) that work for vanilla clients and in which no item can move.
- **Scheduler** (`scheduler()`) on the server thread.
- **Metrics** (`metrics()`): lock-free timers, counters and gauges for tools such as MejtaPerf. Core reports its own notification queues and scheduler.
- One delivery queue and thread per notification provider, and in-game mutes that survive restarts.
- `MejtaCoreAPI.hasPermission(server, profile, permission)` for players who may be offline.

### Fixed (found in the release review)
- The first audit entry after startup could block the caller on a disk read. It could even deadlock when recorded from an I/O completion. Audit lines are now linked and written on the I/O thread.
- A full I/O queue or a crash mid-line made `audit verify` report tampering that never happened.
- `DataStore.keys()` could miss a key that was being written. A queued write could overwrite a newer `writeNow`. A file recovered from its backup was missing again on the next read.
- A player without permission could flood the audit log and notification providers with refused config edits from a modified client.
- A very long text setting could disconnect staff who opened Server Settings.
- Non-secret settings whose key contains "token", "secret", "password" or "webhook" were hidden. This also hid the permission level of `mejtacore.config.secrets`.
- Single-player "Reset All" also reset secrets.
- A failing vanish provider revealed hidden players; presence now fails closed.
