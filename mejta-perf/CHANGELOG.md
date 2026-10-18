# MejtaPerf changelog

## 1.0.0

First release.

- **Tick monitor:**
  - TPS over 5 s, 1, 5 and 15 minutes.
  - MSPT mean, median, p95 and max.
  - Tick time per dimension.
  - It is always on and costs two clock reads per tick event.
- **Lag alerts** on `mejtaperf.lag`:
  - With sustain time, hysteresis and cooldown.
  - They list possible contributors (entities, chunks, slowest dimension, most expensive Mejta metrics) and never claim a cause.
  - A watchdog warns while one tick is stuck.
- **Profiler** (`/perf profiler start|stop|status`):
  - Samples the server thread with Java stack traces and separates real work from waiting for the next tick.
  - Charges time to mods, counting mixin handlers for the mod that added them.
  - Saves a report with mods, hottest methods and the call tree.
- **`/perf`, `/perf entities`, `/perf metrics [addon]`** and **`/perf report`**: status in chat, and full text reports in `mejta-data/mejtaperf/reports/`.
- **Privacy:** no player names, addresses or secrets in notifications or reports. The one exception is that a profile report names who started it.
