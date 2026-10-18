# MejtaPerf

MejtaPerf (mod ID `mejtaperf`) is a performance addon for **MejtaCore**, for finding out why a server lags. It provides:

1. **A tick monitor.** It is always on and costs almost nothing. It tracks TPS and MSPT (milliseconds per tick) over 5 seconds, 1, 5 and 15 minutes, plus the tick time of each dimension.
2. **Lag alerts.** These are MejtaCore notifications that staff see in chat. MejtaDiscord can forward them to Discord.
3. **A profiler.** You start it with a command. It samples the server thread and works out which mod the time belongs to, Spark-style, using only Java (no native agent). It saves its findings as a report.
4. **A view of Mejta metrics.** This shows the time each MejtaAC hook, MejtaCore's notification queues and the scheduler cost, through MejtaCore's metrics API.
5. **Reports.** A text file with everything above, for sharing or comparing over time.

MejtaPerf reads other addons only through MejtaCore's metrics. Nothing requires it: without MejtaPerf, addons still report their metrics and nobody reads them.

| | |
|---|---|
| Version | 1.0.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | **MejtaCore 1.3.0 or newer, below 2.0.0** (API version 4, for metrics) |
| Third-party libraries | None |
| Runs on | The server. In singleplayer it runs in the integrated server. |

## Installing

Put `mejtaperf-1.0.0.jar` in the server's `mods/` folder next to MejtaCore and Fabric API, then restart. The settings file `config/mejta/mejtaperf.json` is created on first start.

## Commands

`/perf` is also available as `/mejtaadmin mejtaperf perf`. The top-level name is not registered if another mod already uses it. Server owners can also turn the name off with MejtaCore's `commands.disabled` setting.

| Command | Permission | What it does |
|---|---|---|
| `/perf` or `/perf status` | `mejtaperf.view` | TPS over four windows; MSPT mean, median, p95 and max for the last minute; players, chunks and entities, overall and per dimension, with each dimension's tick time; memory, garbage collection, CPU and threads; profiler state. |
| `/perf entities` | `mejtaperf.view` | The 10 most common entity types, and the 5 chunks with the most entities (with coordinates). |
| `/perf metrics [addon]` | `mejtaperf.view` | Mejta metrics over the last minute. For timers: ms per tick, calls per second, average and peak. For counters: rate. For gauges: current value. |
| `/perf profiler start [seconds]` | `mejtaperf.profiler` | Samples the server thread until stopped, until the time given (5–3600 s), or until `profiler.max_seconds`. |
| `/perf profiler stop` | `mejtaperf.profiler` | Ends the profile early. The report is still saved. |
| `/perf profiler status` | `mejtaperf.profiler` | Whether a profile is running, and where the last report is. |
| `/perf report` | `mejtaperf.profiler` | Saves a full report (see [Reports](#reports)). |

Starting and stopping the profiler and saving a report are recorded in MejtaCore's audit log, along with who did it.

## Permissions

| Node | Default | |
|---|---|---|
| `mejtaperf.view` | moderators (level 1) | See `/perf`, `/perf entities` and `/perf metrics` |
| `mejtaperf.alerts` | moderators | Receive lag alerts in chat |
| `mejtaperf.profiler` | admins (level 3) | Run the profiler and save reports; receive "profile saved" messages |

Levels can be changed per node with `/mejtaadmin config set mejtacore.permissions <node> <level>`.

## Settings (`config/mejta/mejtaperf.json`)

| Key | Default | |
|---|---|---|
| `alerts.enabled` | `true` | Publish lag alerts on the channel `mejtaperf.lag` |
| `alerts.mspt_threshold` | `50` (10–1000) | MSPT that counts as lag. 50 ms is the most a tick can take at 20 TPS. |
| `alerts.sustain_seconds` | `10` (1–600) | How long MSPT must stay at or above the threshold before an alert, and below 80% of it before the recovery |
| `alerts.cooldown_minutes` | `10` (1–1440) | Minimum time between two lag alerts |
| `alerts.recovery` | `true` | Also publish a message when the server has recovered |
| `alerts.auto_profile` | `false`, **experimental** | Start a 30-second profile when a lag alert fires, and save its report |
| `profiler.interval_ms` | `10` (2–100) | Time between two samples |
| `profiler.max_seconds` | `600` (10–3600) | A profile stops by itself after this long |
| `reports.keep` | `20` (1–500) | Reports of each kind that are kept; older ones are deleted |

Change settings with `/mejtaadmin config set mejtaperf <key> <value>` or in MejtaCore's settings menu. They take effect immediately.

## How the numbers are measured

- **MSPT** is the time from the start to the end of a server tick, measured with Fabric's tick events. This is the server's own work, as on vanilla's debug screen. The pause until the next tick is not counted.
- **TPS** is the number of ticks that started in a window, divided by its length. Right after startup, only the time covered so far counts.
- **A dedicated server paused because it is empty** (`pause-when-empty-seconds`) skips the rest of each tick, and Fabric's end-of-tick event with it. Such ticks are not recorded, and do not count as time inside a tick.
- **Dimension tick time** is the same measurement for each dimension's own tick, averaged over the last minute. It covers entities, block entities, chunks and weather. Everything else in a server tick (players' connections, scheduled tasks, autosaves) is not part of any dimension.
- **Garbage collection** is the collector time reported by Java, over the last minute. With concurrent collectors (G1, ZGC) this time does not all pause the server.
- **Metrics** are cumulative values that addons report through MejtaCore. MejtaPerf reads them once per second and shows the change over the last minute. A timer's ms per tick includes work on other threads, such as MejtaCore's notification delivery. That work costs CPU but does not make ticks longer.

The monitor's memory use is fixed: two arrays of 18,000 numbers for the tick history, and a minute of per-second snapshots.

## Lag alerts

An alert fires when the mean MSPT stays at or above `alerts.mspt_threshold` for `alerts.sustain_seconds`. After that, the cooldown applies. A single tick longer than the sustain time counts as well, such as a 15-second freeze: the alert then covers that tick.

The alert is a WARNING notification on `mejtaperf.lag`. It contains:
- MSPT and TPS over the sustain window (for a single freeze, over a window that includes it);
- players, loaded chunks and entities;
- the three most common entity types;
- the slowest dimension;
- the three most expensive Mejta metrics.

These are listed as **possible contributors, not a diagnosis**: a high entity count is not proof that entities cause the lag. The profiler shows where the time actually goes.

The recovery message (INFO) is sent once MSPT stays below 80% of the threshold for the sustain time. The margin prevents an alert and a recovery every few seconds when MSPT hovers around the threshold.

**A server stuck in one tick.** The alarm learns about a tick only when it ends, so a server that hangs (a deadlock, an endless loop) would never alert. A watchdog thread (`MejtaPerf-Watchdog`) therefore checks once a second whether the current tick has run for longer than `alerts.sustain_seconds`. If so, it publishes a WARNING on `mejtaperf.lag`, once per stuck tick: "The server has been stuck in one tick for N s". It respects `alerts.enabled`. Players in game see it only once the server thread runs again, but MejtaDiscord can forward it straight away. If the tick ends after all, the usual lag alert follows.

To send alerts to Discord, add `mejtaperf.*` to MejtaDiscord's routing, for example:

```
/mejtaadmin config set mejtadiscord notify.routing mejtaac.*=default; mejtaperf.*=staff
```

## The profiler

The profiler takes a stack trace of the server thread every `profiler.interval_ms`. It does this on its own thread, the way Spark's Java sampler does, and merges the samples into a call tree.

- **Phases.** Each sample is marked as taken **in a tick** or **between ticks**. Between ticks, the server handles network packets (moves, attacks, chat) and waits for the next tick.
- **Waiting.** Samples where the server was only waiting for the next tick are counted but left out of every percentage. That is decided from the stack, not the phase: the thread must be parked, sleeping or yielding, and above that there must be only the main loop's wait (`MinecraftServer.waitUntilNextTick`, through its `managedBlock` and `waitForTasks`), or, between ticks, only the server's own loop. Every other wait is lag and stays in: a tick waiting for a chunk to load (`ServerChunkCache.getChunk`), an autosave waiting for chunks to be written (`ChunkMap.saveAllChunks`), or a packet handler blocked on a lock. These wait through the same `managedBlock` or park as the idle loop, but are called from somewhere else.
- **Time by mod.** Each sample is charged to the innermost frame that belongs to a mod other than Minecraft. If a mod's code is anywhere on the stack, the time was spent on its behalf, even when the innermost frames are vanilla code it called. Mixin handlers count for the mod that added them: Fabric names them `handler$…$<modid>$…`. Mods are found from the files they were loaded from; for mods nested inside another mod's jar, MejtaPerf falls back to the package name.
- **Cost.** Taking a stack briefly pauses the server thread at a safepoint. At the default 10 ms interval the cost is small, but a profile is meant to run for minutes, not permanently. Memory is bounded: the tree holds at most 200,000 nodes, and if it fills up, the report says how many samples were cut short.

When the profile ends, the report is built and saved on MejtaPerf's own I/O thread. Holders of `mejtaperf.profiler` then see a "Profile saved" message with the top mods and methods. That message is published on `mejtaperf.profiler`, so it can be routed to Discord too. It does not say who started the profile ("started from a command"); the report file does. The report's TPS covers the whole profile. Its MSPT covers the whole profile too, up to the 15 minutes of tick history; the report says when it covers only the end of a longer profile.

If the server stops while a profile is sampling, the profile is dropped without a report. A profile that had already ended still has its report written, and the shutdown waits up to 5 seconds for it.

## Reports

Reports are text files in `mejta-data/mejtaperf/reports/`:

- `profile-<UTC time>.txt` contains:
  - duration, samples, and how many were waiting;
  - time by mod;
  - the 50 hottest methods (self time);
  - the call tree, with branches under 0.5% hidden.
- `report-<UTC time>.txt` contains:
  - versions and uptime;
  - ticks over four windows;
  - dimensions, entity types and crowded chunks;
  - memory, garbage collection and CPU;
  - all Mejta metrics;
  - Mejta addons and their states;
  - the list of installed mods with versions.

Each file is written to a temporary file first and then moved into place, so it is never half written. Only the newest `reports.keep` of each kind are kept. Reports contain no player names, addresses or settings values. The one exception: a profile report started from a command names who started it, as the audit log does.

## Building and testing

```
cd mejta-core && ./gradlew publishToMavenLocal
cd ../mejta-perf && ./gradlew test build runClientGameTest
```

- **`test`** runs 25 JUnit tests. They cover TPS and MSPT windows, the 15-minute ring buffer, ticks that never end (a server paused while empty), the lag alarm (sustain, hysteresis, cooldown, single long freezes), the stuck-tick watchdog's decisions, dimension times, metric rates, the call tree and its limit, mod attribution (mixin handlers, lambdas, package fallback), idle detection on real server stacks (chunk waits, autosaves, blocked packet handlers), the report text, the sampler against a real thread, report files with retention and numbered names, and the I/O queue's drain at shutdown.
- **`runClientGameTest`** starts a real client and dedicated server and checks:
  - the commands are hidden without permission;
  - status, entities and metrics show real values;
  - a full report is saved, without player names;
  - the profiler samples the real server thread, and its report has mods, methods and the tree;
  - the "Profile saved" message names nobody;
  - an artificial 45 ms per tick of extra work raises a lag alert, and then a recovery.

  Every check logs `[MejtaPerfTest] PASS/FAIL`.

The repository's CI runs both on every push.

## Limitations

- Only the server thread is profiled. Chunk generation and lighting worker threads, and the network threads, are not sampled.
- The profiler uses Java stack sampling, not async-profiler. Time in native code shows as the Java method that called it, and samples land only at safepoints. Very short methods may therefore be under- or over-counted, as with every Java sampler.
- The first tick after a dedicated server resumes from its pause while empty is sampled as "between ticks", and is not watched for a stall.
- Entity counts and crowded chunks are collected by walking every entity once, on the server thread, when a command or alert asks for them. This is not done every tick.
- There is no in-game graph or web viewer. Reports are plain text.
