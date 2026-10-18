# MejtaAC changelog

## 1.0.0

First release. Requires MejtaCore 1.3.0 or newer (API version 4).

- **Pipeline:** detect, assess (violation levels that decay), decide (`OFF`, `DRY_RUN` or `ENFORCE`, plus a minimum confidence), report (log, records, staff alerts) and kick. Starts in `DRY_RUN`.
- **Tick sync:** a ping per player per server tick tells MejtaAC which server tick each vanilla client has processed, and the round-trip time, without any client mod.
- **12 checks:**
  - `protocol.invalid_move` (stable): impossible pitch, NaN or infinite values, positions out of bounds.
  - `movement.timer`: more client ticks than real time allows.
  - `movement.ground_spoof`: claiming ground with nothing below (also NoFall).
  - `movement.fly`: hovering, flying up and gliding, against vanilla's gravity.
  - `movement.jump`: take-offs higher than vanilla allows.
  - `movement.speed`: horizontal movement faster than vanilla physics.
  - `combat.reach`: attacks beyond the weapon's range, with latency compensation.
  - `combat.hitbox`: attacks on entities the player wasn't looking at.
  - `combat.multi_target` (stable): two entities attacked in one client tick.
  - `interaction.block_reach`: block actions beyond the interaction range.
  - `interaction.fast_break`: blocks broken faster than tool and effects allow.
  - `vehicle.boat_fly`: boats that hover or fall slower than gravity.
- **Commands:** `/mejtaadmin mejtaac status`, `alerts`, `vl`, `history` and `reset`.
- **Integrations:** alerts and kicks go through MejtaCore's notifications, so MejtaDiscord can forward them. Each hook's cost shows in MejtaPerf (`/mperf metrics mejtaac`).

Fixes from the release review, before the first release:
- **Enforcement:**
  - Only flags at `enforcement.min_confidence` or above now count towards a kick. Each check keeps a separate enforceable violation level, so MEDIUM flags followed by one HIGH flag no longer kick. Alerts, records and commands still show the total.
- **Server safety:**
  - Starting to break a block far away no longer makes the server load or generate that chunk. Fast break only looks at blocks within reach.
- **False positives:**
  - Reach, hitbox and block reach wait after a teleport or respawn until the client has confirmed it, instead of measuring from the new position.
  - Ground spoof no longer counts claims during a network stall. With tick sync it waits until the client has seen the ground go; without it, it counts real arrival time, so a catch-up burst counts as almost nothing.
  - Ground spoof, fly, speed and jump ignore players watching the End credits.
  - Knockback and other server changes sent between two ticks now count as applied only after the next tick's ping is answered.
  - Changes of flight abilities and game mode are now treated like server-applied velocity.
  - Timer: arrivals that fall behind gradually are now kept as temporary credit for the catch-up burst, still for 2 s at most.
  - Speed and fly ignore players next to pistons.
  - Speed allows the sprint-jump boost under a ceiling just above the head.
- **Single-player:** state is cleared when the integrated server stops, so a world opened afterwards starts clean.
