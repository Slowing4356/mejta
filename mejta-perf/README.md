# MejtaPerf

MejtaPerf (mod ID `mejtaperf`) helps you find out why a server lags. It runs on the server (in singleplayer, the integrated server) and provides:
- **a tick monitor**, always on and nearly free: TPS and MSPT (milliseconds per tick) over 5 seconds, 1, 5 and 15 minutes, plus each dimension's tick time;
- **lag alerts**, sent to staff in chat through MejtaCore, which MejtaDiscord can forward;
- **a profiler** that samples the server thread and shows which mods and methods the time goes to;
- **Mejta metrics**: what each MejtaCore addon hook costs;
- **reports**: plain-text files for sharing or comparing over time.

| | |
|---|---|
| Version | 1.0.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.4.0 (any version from 1.3.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None |

## Installing

Put `mejtacore-1.4.0.jar`, `mejtaperf-1.0.0.jar` and Fabric API in the server's `mods/` folder, then restart. The settings file `config/mejta/mejtaperf.json` is created on first start.

## Commands

The command is `/mperf`, because `/perf` is vanilla's own profiler command. It is also available as `/mejtaadmin mejtaperf mperf`, in case another mod uses the name. You can turn off the top-level name with MejtaCore's `commands.disabled` setting.

| Command | Permission | What it does |
|---|---|---|
| `/mperf` or `/mperf status` | `mejtaperf.view` | TPS over four windows; MSPT mean, median, p95 and max for the last minute; players, chunks and entities per dimension, with tick times; memory, garbage collection, CPU and threads; profiler state |
| `/mperf entities` | `mejtaperf.view` | The 10 most common entity types and the 5 chunks with the most entities |
| `/mperf metrics [addon]` | `mejtaperf.view` | Mejta metrics over the last minute |
| `/mperf profiler start [seconds]` | `mejtaperf.profiler` | Profile until stopped, for the given time (5–3600 s), or until `profiler.max_seconds` |
| `/mperf profiler slow <ms> [seconds]` | `mejtaperf.profiler` | Like `start`, but keep only ticks that took at least `<ms>` (10–60000). Use it for lag spikes. |
| `/mperf profiler stop` | `mejtaperf.profiler` | End the profile early. The report is still saved. |
| `/mperf profiler status` | `mejtaperf.profiler` | Whether a profile is running, and where the last report is |
| `/mperf report` | `mejtaperf.profiler` | Save a full report |

Starting and stopping the profiler and saving a report are recorded in MejtaCore's audit log.

## Permissions

Change levels in `config/mejta/mejtacore.permissions.json`.

| Node | Default level | Allows |
|---|---|---|
| `mejtaperf.view` | 1 (moderators) | `/mperf`, `entities` and `metrics` |
| `mejtaperf.alerts` | 1 (moderators) | Receive lag alerts in chat |
| `mejtaperf.profiler` | 3 (admins) | Run the profiler, save reports, receive "Profile saved" messages |

## Settings

Stored in `config/mejta/mejtaperf.json`. Change them with `/mejtaadmin config set mejtaperf <key> <value>` or MejtaCore's settings menu; they apply at once.

| Key | Default | Meaning |
|---|---|---|
| `alerts.enabled` | `true` | Publish lag alerts on `mejtaperf.lag` |
| `alerts.mspt_threshold` | `50` (10–1000) | MSPT that counts as lag. 50 ms is the most a tick can take at 20 TPS. |
| `alerts.sustain_seconds` | `10` (1–600) | How long MSPT must stay at or above the threshold before an alert, and below 80% of it before the recovery |
| `alerts.cooldown_minutes` | `10` (1–1440) | Minimum time between two lag alerts |
| `alerts.recovery` | `true` | Also publish a message when the server recovers |
| `alerts.auto_profile` | `false` | **Experimental.** When the lag alarm trips, profile for 30 seconds (only ticks at or above the threshold) and save the report. Works even when `alerts.enabled` is off |
| `profiler.interval_ms` | `10` (2–100) | Time between two samples. Lower is more precise and costs more. |
| `profiler.max_seconds` | `600` (10–3600) | A profile stops by itself after this long |
| `reports.keep` | `20` (1–500) | Reports of each kind kept; older ones are deleted |

## How the numbers are measured

- **MSPT** is the time from the start to the end of a server tick, from Fabric's tick events. It is the server's own work, as on vanilla's debug screen; the pause until the next tick is not counted.
- **TPS** is the number of ticks that started in a window, divided by its length.
- **Ticks skipped** while a dedicated server is paused because it is empty are not recorded.
- **Dimension tick time** is the same measurement for each dimension's own tick (entities, block entities, chunks, weather), averaged over the last minute. Network handling, scheduled tasks and autosaves are not part of any dimension.
- **Garbage collection** is the collector time Java reports over the last minute. With concurrent collectors (G1, ZGC), not all of it pauses the server.
- **Metrics** are values that addons report through MejtaCore, read once a second. A timer's ms per tick includes work on other threads, which costs CPU but does not lengthen ticks.

The monitor uses a fixed amount of memory: 15 minutes of tick history.

## Lag alerts

An alert fires when mean MSPT stays at or above `alerts.mspt_threshold` for `alerts.sustain_seconds`; then the cooldown applies. A single tick longer than the sustain time also counts.

The alert is a WARNING on `mejtaperf.lag`, sent in game to holders of `mejtaperf.alerts`. It lists MSPT and TPS, players, chunks, entities, the three most common entity types, the slowest dimension and the three most expensive Mejta metrics. These are **possible contributors, not a diagnosis**. The profiler shows where the time really goes.

The recovery message (INFO) is sent once MSPT stays below 80% of the threshold for the sustain time, so a server hovering around the threshold does not flap.

**A server stuck in one tick** (a deadlock or endless loop) would never finish a tick. A watchdog thread checks once a second and publishes "The server has been stuck in one tick for N s" once per stuck tick, if `alerts.enabled` is on. Players see it only when the server thread runs again, but MejtaDiscord can forward it at once.

To send alerts to Discord, add `mejtaperf.*` to MejtaDiscord's `notify.routing`, for example `mejtaac.*=default; mejtaperf.*=staff`.

## The profiler

- **Sampling.** Every `profiler.interval_ms`, a separate thread takes a stack trace of the server thread and adds it to a call tree. Only Java is used; no native agent.
- **Waiting.** Samples where the server is only idling until the next tick are counted but left out of every percentage. Waits that are real lag, such as a tick waiting for a chunk to load or an autosave, stay in.
- **Time by mod.** Each sample is charged to the innermost mod (other than Minecraft) on the stack. Mixin handlers count for the mod that added them.
- **Slow ticks only.** `/mperf profiler slow <ms>` keeps only the samples of ticks that took at least `<ms>`, so normal ticks do not dilute a spike. The report says how many slow ticks were found.
- **Cost.** Each sample briefly pauses the server thread. At the default interval this is small, but run profiles for minutes, not permanently. Memory is capped; the report says if the tree filled up.

When a profile ends, holders of `mejtaperf.profiler` see a "Profile saved" message with the top mods and methods, published on `mejtaperf.profiler`. That message never names who started the profile; the report file does. If the server stops while a profile is running, the profile is dropped without a report.

## Reports

Reports are text files in `mejta-data/mejtaperf/reports/`:
- `profile-<UTC time>.txt`: duration and samples, time by mod, the 50 hottest methods, and the call tree (branches under 0.5% hidden).
- `report-<UTC time>.txt`: versions and uptime, ticks over four windows, dimensions, entity types and crowded chunks, memory, garbage collection and CPU, all Mejta metrics, Mejta addons and their states, and installed mods.

Files are written whole (via a temporary file), and only the newest `reports.keep` of each kind are kept. Reports contain no player names, addresses or settings values, except that a profile report names who started it, as the audit log does.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.4.0 first
cd ../mejta-perf && ./gradlew build              # compile and run unit tests
./gradlew runClientGameTest                      # client game test
```

- **Unit tests** cover TPS and MSPT windows, the lag alarm and stuck-tick detection, dimension times, metric rates, the call tree, mod attribution, idle detection on real server stacks, report text and files, and the I/O queue.
- **The client game test** joins a dedicated server with a real client. It checks that commands are hidden without permission, that status, entities and metrics show real values, that a report and a profile are saved with the expected content and no player names, and that extra work per tick raises a lag alert and then a recovery.

## Limitations

- Only the server thread is profiled. Worker threads (chunk generation, lighting) and network threads are not sampled.
- Java stack sampling only lands at safepoints, and native code shows as the Java method that called it. Very short methods may be under- or over-counted.
- Entity counts walk every entity once, on the server thread, when a command or alert asks for them.
- There is no in-game graph or web viewer. Reports are plain text.
