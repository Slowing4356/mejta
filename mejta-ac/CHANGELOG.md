# MejtaAC changelog

## 1.1.0

Requires MejtaCore 1.3.0 or newer (API version 4). Every new setting is off or experimental; with the defaults, existing checks behave exactly as in 1.0.0.

- **Mitigation (experimental, off by default):** with `enforcement.mitigation` set to `ON`, every flag at `enforcement.min_confidence` or above undoes the action behind it, in `DRY_RUN` as in `ENFORCE`, independently of violation levels and kicks:
  - `movement.fly`, `movement.speed`, `movement.jump` and `movement.elytra`: the player is set back to the last safe position with vanilla's own position correction. Never into a block, at most every 0.25 s, never while a teleport is unconfirmed.
  - `combat.reach` and `combat.multi_target`: the attack is cancelled before vanilla processes it.
  - `combat.hitbox` (judged after vanilla has processed the hit): the player's next attacks are cancelled for 10 client ticks.
  - `interaction.block_reach` and `interaction.fast_break`: the block action is cancelled, and the client is resynchronised (sequence acknowledged, real block states sent).
  - Records, alerts and log lines say what was mitigated (`"mitigation": "setback"`, `cancel_attack`, `block_attacks` or `cancel_action`). `/mejtaadmin mejtaac status` shows the setting.
- **2 new checks (14 in total), both experimental and MEDIUM at most:**
  - `movement.elytra`: elytra flight that ignores glide physics without a firework boost (elytra fly, glide speed, boosting without a rocket). `movement.fly` and `movement.speed` still don't judge gliding players.
  - `vehicle.horse`: horses, donkeys, mules, skeleton and zombie horses and camels that move faster or jump higher than their own speed and jump-strength attributes allow, or hover and fly.
- **Fix:** `movement.speed` no longer judges a landing from an elytra glide. Only the server ends a glide, so the client keeps gliding (sliding along the ground) until that change arrives one round trip later; this flagged legitimate landings at every latency. The prediction now waits until the client has confirmed a server tick after the glide ended, plus 5 client ticks.
- **Tests:** unit tests for mitigation decisions, glide physics (including 30 random legitimate flights) and mount physics. A new latency suite, `MejtaAcStage6GameTest`, covers legitimate gliding with real input and fireworks, legitimate horse and camel riding, the new cheats, and every mitigation. The Stage 4 and Stage 5 legitimate-play scenarios now also require zero `movement.elytra` and `vehicle.horse` flags.
- **Internal:** the block-action hooks pass the packet's block-change sequence and clicked face to MejtaAC, so a cancelled action can be resynchronised like vanilla's own refusals.

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
