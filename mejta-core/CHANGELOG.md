# MejtaCore changelog

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

### Notes
- The event bus, the action registry and most of the offline permission check proposed for API 4 are deferred: no current addon needs them.
