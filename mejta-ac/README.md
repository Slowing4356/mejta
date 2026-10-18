# MejtaAC

MejtaAC (mod ID `mejtaac`) is a server-side anti-cheat addon for **MejtaCore**. It uses MejtaCore's configuration, permissions, server commands, notifications and record storage instead of building its own.

| | |
|---|---|
| Version | 0.5.0 (12 checks: protocol, timer, ground spoof, fly, jump, speed, reach, hitbox, multi-target, block reach, fast break, boat fly) |
| Minecraft | 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | **MejtaCore 1.1.0 or newer, below 2.0.0** (API version 2). MejtaCore 1.2.0+ adds in-game editing of MejtaAC's settings |
| Third-party libraries | None |

## Installing

Put `mejtacore-1.2.1.jar`, `mejtaac-0.5.0.jar` and Fabric API in the **server's** `mods/` folder. In single-player, MejtaAC runs on the integrated server, so install everything on the client.

| | Dedicated server | Player's client |
|---|---|---|
| MejtaAC | **Required** | **Not needed.** All checks run on the server; vanilla clients can join. |
| MejtaCore | **Required** | Optional: only for the `/mejta` menu, including editing MejtaAC's settings in-game (MejtaCore 1.2.0+ on both sides) |
| Fabric API | **Required** | Only if the client has MejtaCore |

Installing MejtaAC on a client does nothing for multiplayer: a client-side anti-cheat could not be trusted anyway.

MejtaAC starts in **DRY_RUN** mode: it detects, alerts and records, but never kicks. Review the alerts and records first, then set `enforcement.mode` to `ENFORCE` if you're happy with them.

## How it works

Each piece of evidence goes through separate stages, and each stage is configured on its own:

1. **Detect.** A check looks at server-side evidence: the raw packet values, before vanilla clamps or wraps them. It returns a *flag* with a reason, a confidence level (`LOW`, `MEDIUM`, `HIGH` or `CERTAIN`) and the measurements it used.
2. **Assess.** Players with the bypass permission are skipped. Otherwise the flag adds to the player's violation level (VL) for that check. VLs decay over time and are cleared when the player disconnects.
3. **Decide.** Enforcement happens only if the flag's confidence is at least `enforcement.min_confidence` **and** the VL has reached the check's threshold. One weak or ambiguous signal never leads to a punishment.
4. **Report.** MejtaAC writes to the server log, writes a violation record and publishes a staff alert. Records and log lines are limited to one per second for each player and check, and the next one reports how many similar flags were skipped ("+N similar"). Alerts have a configurable cooldown.
5. **Act.** In `ENFORCE` mode the player is kicked. In `DRY_RUN` mode MejtaAC records and alerts "would be kicked" instead. Either way an audit record is written.

## Checks

### `protocol.invalid_move` (stable)

This check flags movement packets containing values an unmodified client can never send. Every reason is deterministic: no timing, physics or latency is involved, so lag can't cause a false positive. All flags have confidence `CERTAIN` and add 1 VL each.

| Reason | Trigger | What vanilla does on its own |
|---|---|---|
| `impossible_pitch` | \|pitch\| > 90° (the vanilla client clamps to ±90) | silently clamps |
| `non_finite_rotation` | NaN or infinite yaw/pitch | disconnects without saying who or why |
| `non_finite_position` | NaN or infinite x/y/z | disconnects (NaN) or clamps (infinity) without logging |
| `position_out_of_bounds` | \|x\| or \|z\| > 30,000,000, or \|y\| > 20,000,000 | silently clamps |

What this check deliberately doesn't do, to avoid false positives:
- **Yaw has no range check.** The vanilla client sends yaw unwrapped: it keeps growing (720, 1080, …) as the player turns. The tests include more than two full turns.
- A packet field the client didn't send is never checked, even if it holds garbage.
- MejtaAC doesn't change how vanilla handles packets. It records first, then vanilla applies its own rules (for example, it still disconnects on NaN). A packet is cancelled only when MejtaAC itself kicks the player.

Typical cause: "derp"/spin modules and some kill auras that send impossible rotations.

### How MejtaAC knows what each player saw (tick sync)

Movement and combat checks have to judge a player by what *their* client showed when it acted, not by the server's present state. MejtaAC works this out without any client mod:

- At the end of every server tick, after that tick's entity updates have been sent, each player gets a ping packet whose ID encodes the tick. The vanilla client answers every ping in order, after processing everything sent before it. So when the answer for tick N arrives, that client had seen tick N.
- The vanilla server normally handles these answers on the network thread, which would let them overtake attack packets sent earlier. MejtaAC puts its answers through vanilla's own packet queue, so they are processed in exactly the order the client sent them.
- The same answers give a per-tick round-trip time. Vanilla's own latency figure comes from one keepalive every 15 seconds, smoothed.
- Pings from other mods are left alone. The cost is one ping of a few bytes per player per tick, sent only while the reach or ground-spoof check is enabled.

The client also sends one tick-end packet per client tick (vanilla since 1.21.2), which gives MejtaAC exact client tick boundaries.

### `movement.timer` (experimental)

This check flags a client that runs more game ticks than real time allows: "timer" cheats that speed up the whole client. It doesn't apply to the single-player host.

- **How:** every client tick-end packet costs one tick of time (50 ms at the normal rate), and real time between arrivals adds credit. A client that keeps getting ahead goes into debt. Arrival times are taken on the network thread, so server lag doesn't bunch them up.
- **Latency handling:**
  - Up to 1 s of credit absorbs jitter.
  - After a gap of more than 300 ms (a network stall or client freeze), the vanilla client sends the missed ticks in a burst. The gap becomes temporary credit that the burst can spend; it expires after 2 s, so it can't be saved for a later speed-up.
  - Slow play can't build up credit beyond 1 s.
  - The check pauses while the server tick rate is changed, frozen or sprinting (`/tick`).
- **Flags:** HIGH confidence, once a client is more than `max_ahead_ms` (default 500 ms) ahead beyond those allowances. A 2× timer is caught after about 1.5 s of running ahead; a 1.3× timer takes about 5 s. Speed-ups below about 1.05× stay within the tolerances by design. See the results table below.

### `movement.ground_spoof` (experimental)

This check flags a client that claims to stand on the ground while nothing is below it. This also catches most NoFall cheats, which claim ground to avoid fall damage.

- **How:** the on-ground flag of the latest movement packet is compared, once per client tick, with the server's own collision test of a box from the player's feet to 0.5 blocks below, widened by 0.1 blocks.
- **Latency handling:** a legitimate client keeps claiming ground until it learns that the block below is gone, which takes up to one round trip. A flag therefore needs `6 + ceil(highest RTT of the last 2 s / 50 ms)` consecutive ticks of the claim, or 26 ticks if the RTT is unknown.
- **Exempt:** riding, flying, elytra, riptide, climbing, water, lava, levitation, sleeping, dimension change, and while a server teleport is unconfirmed.
- **Flags:** HIGH confidence.

### `movement.speed` (experimental)

This check flags horizontal movement faster than vanilla physics allows: speed, strafe and bunny-hop cheats.

- **How:** vanilla horizontal movement per tick is `previous × decay + acceleration`.
  - **On the ground:** decay is `block friction × 0.91` and acceleration is `movement speed × 0.216 / friction³`.
  - **In the air:** 0.91 and 0.02 (0.026 when sprinting).
  - **Sprint-jump:** adds 0.2 once, on take-off.
- **Allowances:** the check uses the player's real movement-speed attribute, which includes sprinting, Speed effects and Soul Speed. It always picks the most permissive possibility, for example the slipperiest block under the feet across the last three ticks, matching vanilla's tick order, and sprinting assumed possible. Sneaking, items in use, soul sand, cobwebs and collisions only make players slower.
- **Not judged (prediction reset):** liquids, vehicles, elytra, riptide, flight, unconfirmed teleports, and server-applied velocity until the client confirms it.
- **Latency:** none needed, because only the sequence of client positions is used. A tolerance of 0.06 blocks per tick absorbs entities pushing the player.
- **Flags:** MEDIUM after the excess builds up; HIGH after 20 consecutive ticks.

### `combat.reach` (experimental)

This check flags attacks on targets farther away than the held weapon allows.

- **Limit:** vanilla's own, from the main-hand item's attack range plus its hitbox margin. Spears, custom weapons and creative reach are respected. It is measured like vanilla, from the eye to the target's bounding box. Vanilla itself allows 3 extra blocks.
- **Latency compensation:**
  - The distance is measured to the **nearest** place the target could have been on the attacker's screen: every position the server recorded between `last confirmed tick − (update interval + 5)` and `last confirmed tick + 1`.
  - Consecutive positions are merged into one box, because the client draws the target part-way between them.
  - The attacker's eye is tried at standing and crouching height.
  - If the client hasn't confirmed a tick in the last 3 s, one second of positions is used and flags are at most MEDIUM.
- **Flags:**
  - Up to `tolerance` (default 0.1) blocks over the limit: none.
  - Up to 0.5 blocks over: MEDIUM, which alerts and records but never enforces by default.
  - More than 0.5 blocks over: HIGH.

### `movement.fly` (experimental)

This check flags vertical movement in the air that ignores gravity: hovering, flying upwards, or gliding (falling slower than gravity allows).

- **How:** in the air, vanilla applies `vy = (vy − gravity) × 0.98` after every move, so each client tick's vertical movement can be predicted from the previous one. The check uses the player's gravity attribute, not a hard-coded value. Only moving *up* relative to the prediction counts, because ceilings, landing and other collisions only ever make the movement smaller.
- **When the prediction is reset** (no flag possible):
  - the client claims to be on the ground; false claims are `movement.ground_spoof`'s job, and this also covers a floor that disappeared before a lagging client noticed;
  - real ground below, or a collision on the predicted path;
  - water, lava, climbing, cobwebs, berry bushes, honey, scaffolding, bubble columns, powder snow;
  - slow falling or levitation, elytra, riptide, vehicles, creative flight, unconfirmed teleports.
- **Server velocity (knockback, explosions, wind charges):** while the server's change is in flight, the check is reset until the client confirms (tick sync) that it has applied it.
- **Jumps and bounces:** the first airborne tick after a reset only establishes the starting velocity, so jumps and slime bounces never flag. A too-high take-off is `movement.jump`'s job.
- **Leaving a vehicle:** the first 10 ticks after dismounting aren't predicted, because vanilla places the player next to the vehicle.
- **Unloaded chunks:** a client whose chunk below isn't loaded falls at a constant 0.098 per tick by design; that exact pattern isn't counted.
- **Latency:** only the sequence of client positions is used, and latency doesn't change it.
- **Flags:** MEDIUM after the excess builds up (about 7 ticks of hovering); HIGH after 20 consecutive violating ticks. At most one flag every 10 ticks.

### `movement.jump` (experimental, since 0.5.0)

This check flags jumps higher than vanilla allows ("high jump"). `movement.fly` can't see these: after a too-high take-off, the rest of the jump follows normal gravity, so only the take-off itself is wrong.

- **How:** vanilla's `jumpFromGround` sets the vertical velocity to `max(jumpPower, current)`, where `jumpPower = JUMP_STRENGTH × block jump factor + Jump Boost`, and the client moves by exactly that in the same tick. So the first airborne tick after standing on the ground can rise at most `jumpPower` (0.42 normally, 0.62 with Jump Boost II). Honey's jump factor and ceilings only lower it.
- **Allowances:**
  - **Effects:** the jump power used is the highest of the last 2 s plus twice the round trip. An effect the server just removed, but the client hasn't heard about yet, is still allowed. This also covers lag and fake lag.
  - **Bounces:** slime blocks and beds may launch a player up to the speed at which they landed.
  - **Step-ups** (stairs, slabs, blocks) never reach the check, because the player stands on the block afterwards.
- **Not judged:**
  - knockback, explosions, teleports (ender pearls, `/tp`) and respawns until the client has applied them;
  - vehicles and the first 10 ticks after leaving one;
  - liquids, climbing, levitation, flight, elytra, riptide;
  - cobwebs, honey, scaffolding, bubble columns, powder snow;
  - pistons and collidable entities (boats, shulkers, minecarts) next to the player.
- **Flags:** a single too-high jump is MEDIUM (one take-off is one sample). It becomes HIGH when the excess is over 0.1 blocks/tick on the third jump within 30 s.

### `vehicle.boat_fly` (experimental, since 0.5.0)

This check flags boats and rafts that hover, rise, or fall slower than gravity allows ("boat fly"). The player steering a boat simulates it on their own client, and the server accepts the positions it reports. Without this check a boat can fly freely.

- **How:** in the air (`AbstractBoat.floatBoat`, status `IN_AIR`), vanilla subtracts the boat's gravity (0.04) from its vertical speed every tick, **without drag**, and then moves the boat. So each airborne tick follows from the one before: `dy = previous dy − 0.04`. Only moving *up* relative to that counts.
- **When the prediction is reset** (no flag possible):
  - water, lava or bubble columns in or up to one block below the boat (buoyancy, snapping to the water surface, bubble launches);
  - ground or a collision on the predicted path (landing, slime bounces, sliding off a ledge);
  - cobwebs and similar blocks, pistons, other collidable entities touching the boat, a leash (a happy ghast can lift boats);
  - the first 3 ticks after mounting;
  - **server corrections of the boat** (vanilla's "moved wrongly" rejections, teleports, pushes, explosions) until the client has applied them (tick sync);
  - dimension changes.
- The first airborne tick after a reset only establishes the velocity, so falling off a ledge or being thrown up by a bubble column never flags.
- **Latency:** only the sequence of boat positions is used. Lag spikes and fake lag don't change it.
- **Flags:** MEDIUM after the excess builds up; HIGH after 20 consecutive violating ticks.
- **Only boats and rafts so far.** Horses, striders, camels and happy ghasts (which may legitimately fly) are not judged yet.

### `combat.hitbox` (experimental)

This check flags attacks on entities the player wasn't looking at, such as kill auras that don't send rotations.

- **Why the timing matters:** the vanilla client attacks only what its crosshair ray hits. Within one client tick it picks the target, sends the attack, and only then sends that tick's movement packet with the rotation it used.
- **How:** each attack is judged at the end of its client tick against two rotations, the one the server had when the attack arrived and the newest one from that tick. The target positions are the same latency-compensated boxes as `combat.reach`, including the weapon's hitbox margin.
- **Flags:**
  - The ray must hit a box grown by 0.1 blocks; otherwise MEDIUM.
  - Missing even with 0.4 blocks of slack is HIGH, and only when the client's tick is confirmed.
- **Not caught:** kill auras that send matching rotations pass this check by design. They are a target for later checks, such as aim analysis.

### `interaction.block_reach` (experimental)

This check flags starting to break, or using or placing against, a block beyond the player's block interaction range: 4.5 in survival, 5 in creative, or whatever the attribute says. Vanilla allows one extra block.

- **How:** blocks don't move, so no compensation is needed. The action is sent before that tick's movement, so the server's current position matches the client's. Standing and crouching eye heights are both tried.
- **Flags:** more than `tolerance` (0.1) over the range is MEDIUM; more than 0.5 over is HIGH.

### `interaction.fast_break` (experimental)

This check flags blocks broken faster than the player's tool, enchantments and effects allow.

- **How:** the vanilla client adds the block's destroy progress once per client tick and finishes when the total reaches 1, so a block needs `ceil(1 / progress per tick)` ticks.
- **Latency:** the check counts the client's own ticks (tick-end packets) between "start breaking" and "finished", so latency and jitter can't shorten the measured time.
- **In the player's favour:** the rate is the faster of the start and finish moments, without the airborne and underwater penalties, so jumping while mining or Haste running out mid-break can't cause a flag. Two ticks of slack are allowed.
- **Flags:**
  - Finishing earlier than that is MEDIUM.
  - Finishing in under 70 % of the time is HIGH; vanilla refuses those breaks anyway, so only a cheat sends them.
- **Not checked:** creative mode and blocks that break instantly.

### `combat.multi_target` (stable)

This check flags attacks on two different entities within one client tick. The vanilla client resolves every queued attack click in a tick against the same crosshair target. The test suite verifies this with real rapid clicking. Spear hits on several entities use a different packet and aren't counted.

- **Latency:** not timing-based, so latency can't cause a flag.
- **Flags:** CERTAIN confidence.

## Configuration

Settings are stored in `config/mejta/mejtaac.json` and managed by MejtaCore: values are validated, and invalid ones fall back to defaults with a warning. There are three ways to change them:

- **In-game, on a server (MejtaCore 1.2.0+ on server and client):** **/mejta → Configuration → Server Settings → MejtaAC**. You need `mejtacore.config.edit` (operator level 3 by default). The server validates and saves every change and logs who made it. Enum settings such as `enforcement.mode` cycle through their options, even though MejtaAC isn't installed on the client.
- **By command, anywhere, including the server console:** `/mejtaadmin config set mejtaac enforcement.mode ENFORCE`, `/mejtaadmin config get mejtaac`.
- **In single-player:** **/mejta → Configuration → MejtaAC**. Or edit the file and run `/mejtaadmin config reload`.

Changes apply immediately, including to checks that are already running. The exception is the `records.*` size limits, which apply after a restart. Disabling a check also clears its violation levels.

| Key | Type | Default | Range | Purpose |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Master switch. When off, no check runs. |
| `log.level` | enum | `NORMAL` | `QUIET`, `NORMAL`, `VERBOSE`, `DEBUG` | Server log detail. `QUIET`: enforcement only. `NORMAL`: flags (rate-limited). `VERBOSE`: adds measurements, ping and world. `DEBUG`: every flag without rate limiting, plus exemptions. Use `DEBUG` to investigate false positives. |
| `violations.decay_per_minute` | decimal | `2.0` | 0–600 | VL removed per minute, per check. `0` means VLs only reset on disconnect or after an enforcement decision. |
| `alerts.enabled` | boolean | `true` | | Publish staff alerts. |
| `alerts.min_vl` | decimal | `1.0` | 0–10000 | Alert only once the player's VL for the check reaches this value. |
| `alerts.cooldown_seconds` | integer | `5` | 0–3600 | Minimum time between alerts for the same player and check. |
| `alerts.enforcement` | boolean | `true` | | Publish every kick and would-be kick on `mejtaac.enforcement` (staff chat, notification file log, Discord if installed), even when `alerts.enabled` is off. |
| `enforcement.mode` | enum | `DRY_RUN` | `OFF`, `DRY_RUN`, `ENFORCE` | What happens when a threshold is reached. |
| `enforcement.min_confidence` | enum | `HIGH` | `LOW`…`CERTAIN` | Flags below this confidence never lead to enforcement. |
| `enforcement.kick_message` | string | `Disconnected by anti-cheat.` | 1–256 chars | Shown to kicked players. |
| `exemptions.bypass_permission` | boolean | `true` | | Players with `mejtaac.bypass` are skipped. |
| `records.enabled` | boolean | `true` | | Write violation and enforcement records. |
| `records.max_file_mb` | integer | `5` | 1–256 | Size at which a record file rotates (restart to apply). |
| `records.max_files` | integer | `5` | 1–100 | Files kept per log (restart to apply). Disk use is at most about `max_file_mb × max_files` per log. |
| `records.retention_days` | integer | `30` | 0–3650 | Rotated files older than this are deleted. `0` keeps them until `max_files` pushes them out (restart to apply). |
| `checks.protocol.invalid_move.enabled` | boolean | `true` | | Turns this check on or off. |
| `checks.protocol.invalid_move.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks. |
| `checks.movement.timer.enabled` | boolean | `true` | | Turns `movement.timer` on or off (experimental). |
| `checks.movement.timer.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks. |
| `checks.movement.timer.max_ahead_ms` | integer | `500` | 200–5000 | How far ahead of real time a client may get, beyond the jitter and lag allowances, before a flag. |
| `checks.movement.ground_spoof.enabled` | boolean | `true` | | Turns `movement.ground_spoof` on or off (experimental). |
| `checks.movement.ground_spoof.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks. |
| `checks.combat.reach.enabled` | boolean | `true` | | Turns `combat.reach` on or off (experimental). |
| `checks.combat.reach.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags add 0.5). |
| `checks.combat.reach.tolerance` | decimal | `0.1` | 0–3 | Extra blocks allowed beyond the weapon's range before any flag. |
| `checks.combat.multi_target.enabled` | boolean | `true` | | Turns `combat.multi_target` on or off. |
| `checks.combat.multi_target.kick_vl` | decimal | `3.0` | 1–10000 | VL at which enforcement kicks. |
| `checks.movement.fly.enabled` | boolean | `true` | | Turns `movement.fly` on or off (experimental). |
| `checks.movement.fly.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.combat.hitbox.enabled` | boolean | `true` | | Turns `combat.hitbox` on or off (experimental). |
| `checks.combat.hitbox.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.interaction.block_reach.enabled` | boolean | `true` | | Turns `interaction.block_reach` on or off (experimental). |
| `checks.interaction.block_reach.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.interaction.block_reach.tolerance` | decimal | `0.1` | 0–3 | Extra blocks allowed beyond the block interaction range before any flag. |
| `checks.movement.speed.enabled` | boolean | `true` | | Turns `movement.speed` on or off (experimental). |
| `checks.movement.speed.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.interaction.fast_break.enabled` | boolean | `true` | | Turns `interaction.fast_break` on or off (experimental). |
| `checks.interaction.fast_break.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.movement.jump.enabled` | boolean | `true` | | Turns `movement.jump` on or off (experimental). |
| `checks.movement.jump.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |
| `checks.vehicle.boat_fly.enabled` | boolean | `true` | | Turns `vehicle.boat_fly` on or off (experimental). |
| `checks.vehicle.boat_fly.kick_vl` | decimal | `5.0` | 1–10000 | VL at which enforcement kicks (HIGH flags add 1, MEDIUM flags 0.5). |

Experimental checks say so in their setting description. They are new and have only been tested with the automated suite, not on a busy real server. Run them in `DRY_RUN` and review their flags before enforcing.

## Permissions

Permissions use Minecraft's operator levels through MejtaCore. Server owners can change the required level of each node in `config/mejta/mejtacore.permissions.json`.

| Node | Default | Grants |
|---|---|---|
| `mejtaac.alerts` | `MODERATORS` (1) | Seeing alerts in chat; the `status`, `alerts`, `vl` and `history` commands |
| `mejtaac.admin` | `GAMEMASTERS` (2) | `reset` |
| `mejtaac.bypass` | `OWNERS` (4) | Exemption from all checks, if `exemptions.bypass_permission` is on |

> Level-4 operators bypass all checks by default. To test MejtaAC on yourself as an operator, set `exemptions.bypass_permission` to `false`, or test with a non-operator account.

## Commands

All commands are under `/mejtaadmin mejtaac` and run on the server.

| Command | Permission | What it does |
|---|---|---|
| `status` | `mejtaac.alerts` | Mode, alert settings, log level, checks, number of players with violations |
| `alerts` | `mejtaac.alerts` | Hides or shows anti-cheat alerts in your own chat until restart |
| `vl <player>` | `mejtaac.alerts` | The player's current violation levels |
| `history <name> [1-50]` | `mejtaac.alerts` | Recent violation records, newest first. Also works for offline players. Read on a background thread. |
| `reset <player>` | `mejtaac.admin` | Clears the player's violation levels |

## Logs, records and alerts

MejtaAC keeps these outputs separate:

| Output | Where | For |
|---|---|---|
| Diagnostic log | Server log, logger `MejtaCore/mejtaac` (detail set by `log.level`) | Developers and false-positive investigation |
| Staff alerts | Notification channel `mejtaac.alert`. The in-game provider shows it to `mejtaac.alerts` holders. | Moderators |
| Violation records | `mejta-data/mejtaac/violations.jsonl` | Investigating players over time (`history`) |
| Enforcement audit | `mejta-data/mejtaac/enforcement.jsonl` and channel `mejtaac.enforcement` | Every kick and dry-run "would kick". The notification carries the player's name and UUID, check, reason, VL, measurements, mode and kick message: everything needed to decide on a ban. It is published even when `alerts.enabled` is off (`alerts.enforcement`, on by default). |
| Notification archive | `mejta-data/mejtacore/notifications.jsonl`, written by MejtaCore 1.2.1+ for every notification of every addon | A local copy of everything Discord would get, also when no Discord addon is installed |
| External notifications | Providers registered with MejtaCore, e.g. MejtaDiscord (planned), which routes each channel to its own Discord channel | Discord |

Example violation record (one JSON object per line):

```json
{"type":"violation","player":"Steve","uuid":"…","check":"protocol.invalid_move","reason":"impossible_pitch","vl":3.0,"action":"NONE","confidence":"CERTAIN","vl_added":1.0,"measurements":{"yaw":12.5,"pitch":120.0},"ping_ms":34,"world":"minecraft:overworld","suppressed":0,"ts":"2026-10-18T04:31:16Z"}
```

Records and alerts contain the player's name and UUID, the check, the reason, the VL, measurements, ping and world. They **never contain IP addresses** or other personal data. Memory use is limited by the number of online players times the number of checks. Disk use is limited by the `records.*` settings.

## Discord and other integrations (future)

MejtaAC doesn't depend on MejtaDiscord and has no Discord code. It publishes to MejtaCore's notification service:

```
MejtaAC ──publish──▶ MejtaCore NotificationService ──▶ in-game provider (built in)
                                                    └─▶ MejtaDiscord provider (optional, separate mod)
```

A MejtaDiscord mod would register a `NotificationProvider`. It would route the channels `mejtaac.alert` and `mejtaac.enforcement` to Discord channels that the server owner configures, sending from MejtaCore's background thread. Nothing is sent anywhere outside the server until such a provider is installed **and** configured. Without it, MejtaAC works the same. The fields a provider receives are listed above.

## Building and testing

```bash
cd ../mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.2.0 into ~/.m2
cd ../mejta-ac && ./gradlew build                 # build/libs/mejtaac-0.5.0.jar (also runs the unit tests)
./gradlew test                                 # unit tests only (JUnit 6), a few seconds
./gradlew runClientGameTest                    # end-to-end tests with a real client, about 30 minutes
```

**Unit tests** (`src/test`, JUnit 6.1.3, test-only) cover the pure logic of every check. For each one they feed legitimate inputs that must not flag and cheat inputs that must, using exact vanilla physics:
- a vanilla jump arc;
- the boat's fall off a ledge and a bubble-column launch;
- walking speed;
- real-time, jittery and frozen-then-bursting tick timing.

They also cover violation levels, throttling and every enforcement decision.

The game test (`src/gametest`) logs `[MejtaAcTest] PASS/FAIL` for each check:
- **Pure logic, legitimate inputs:** normal positions, the world border, pitch exactly ±90, huge unwrapped yaw, and fields the client didn't send. None may flag.
- **Pure logic, suspicious inputs:** each reason, measurements, confidence; VL accumulation and decay; rate limiting; every enforcement decision (including "a low-confidence flag never enforces").
- **Single-player, real client:** walking, jumping and turning more than two full circles (no flags); crafted pitch-120 packets (VL, staff alert and its cooldown, rate-limited record with measurements, no IP address); DRY_RUN would-kick audit; bypass permission and its setting; live config changes and a reload from disk; all commands, including muting alerts.
- **Dedicated server, non-op player:** ENFORCE kicks at the threshold with the configured message and writes an audit record; state is cleaned up on disconnect. A NaN rotation is recorded before vanilla's own disconnect.

### Stage 4 latency tests

The suite `MejtaAcStage4GameTest` runs a real client on an in-process dedicated server as a non-op. The client goes through a test-only Netty handler that adds delay and jitter in both directions and can freeze the connection; it doesn't reorder packets, just as TCP doesn't. Netty already ships with Minecraft. For each profile the suite does three things:

1. **Checks the latency measurement:** the RTT measured from pongs must match the simulated RTT.
2. **Plays legitimately and requires zero flags from all nine timing-sensitive checks:**
   - idling, walking and jumping, plus a 1.5 s freeze in the jitter profile;
   - hitting a target that moves **away** at 0.3 blocks/tick, with real crosshair attacks whenever the client sees it within 2.8 blocks;
   - four clicks queued in one tick, which also verifies the premise behind `multi_target`;
   - the floor under the player being removed;
   - breaking and placing blocks with real input;
   - a server knockback launch, which the test asserts really lifted the player 4.9 blocks;
   - an 11-block fall onto slime, which the test asserts produced 7–8 real bounces;
   - sprint-jumping with real input, also with Speed II and on packed ice; the test asserts the speeds actually reached (0.60, 0.66 and 0.47 blocks/tick);
   - mining dirt by hand with real input, which the test asserts really broke the block.
3. **Sends crafted cheats and requires detection:**
   - attacks at 4.2 blocks (HIGH), 3.3 blocks (MEDIUM only) and 3.05 blocks (no flag);
   - an attack while looking the other way (hitbox);
   - two targets attacked in one tick;
   - extra tick-end packets at 2×, 1.3× and 1.05× (the last must stay undetected);
   - ground spoofing during a 40-block fall;
   - a real client forced to hover, fly up or glide (fly);
   - block actions at 5.5 blocks (HIGH), 4.74 blocks (MEDIUM) and 3.54 blocks (no flag);
   - a real client forced to 0.5 blocks/tick along the ground (speed);
   - a dirt block (15 ticks by hand) "finished" after 3 ticks (HIGH), 11 ticks (MEDIUM) and 16 ticks (no flag).

The moving-target scenario is the key compensation test. The suite also records how far away the target was on the server at the moment each legitimate hit arrived. That is what a check without compensation would measure, and for profiles of 300 ms and above it must exceed 3.6 blocks.

**Results** (2026-10-18, MejtaAC 0.4.0 on MejtaCore 1.2.0; 269 checks passed, 0 failed; Stage 3 suite 68/68). Times are from the first cheat packet to the flag:

| Profile | Measured RTT | Legitimate-play flags | Timer (2× / 1.3× / 1.05×) | Ground spoof | Fly: first / HIGH | Speed: first / HIGH | Reach (crafted) | Hitbox (crafted) | Multi-target | Block reach (crafted) | Fast break (crafted) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 ms | 7 ms | **0** (18 hits, up to 4.25 blocks uncompensated) | 1.45 s / 4.6 s / below tolerance | 0.40 s | 0.40 s / 1.40 s | 0.15 s / 1.15 s | 4.2: HIGH, 3.3: MEDIUM, 3.05: none | HIGH, 0.05 s | immediate | 5.5: HIGH, 4.74: MEDIUM, 3.54: none | 3/15: HIGH, 11/15: MEDIUM, 16/15: none |
| 150 ms | 175 ms | **0** (12 hits, up to 4.55 blocks) | 1.55 s / 4.7 s / below tolerance | 0.65 s | 0.45 s / 1.45 s | 0.19 s / 1.20 s | same | HIGH, 0.10 s | 0.09 s | same | same |
| 300 ms | 316 ms | **0** (17 hits, up to 5.45 blocks) | 1.55 s / 4.7 s / below tolerance | 0.95 s | 0.50 s / 1.50–1.55 s | 0.25 s / 1.25 s | same | HIGH, 0.15 s | 0.15 s | same | same |
| 500 ms | 517 ms | **0** (14 hits, up to 6.65 blocks) | 1.64 s / 4.8 s / below tolerance | 1.35 s | 0.65 s / 1.60–1.65 s | 0.35 s / 1.35 s | same | HIGH, 0.30 s | 0.25 s | same | same |
| 300 ms ±80 ms + 1.5 s freeze | 312 ms | **0** (15 hits, up to 5.75 blocks) | 1.60 s / 4.8 s / below tolerance | 1.05 s | 0.50–0.55 s / 1.55 s | 0.30 s / 1.30 s | same | HIGH, 0.20 s | 0.20 s | same | same |

What the results show:
- **Compensation is needed at any latency.** At 0 ms, legitimate hits on a target moving away still arrive when the server-side target is up to 4.25 blocks away. This comes from vanilla's entity update interval and client interpolation alone, and network latency adds to it (6.65 blocks at 500 ms). None of these hits was flagged by reach or hitbox.
- **Physics, not thresholds.** Legitimate sprint-jumping peaked at 0.60 blocks/tick and Speed II at 0.66, both faster than the 0.5 blocks/tick cheat. They aren't flagged because each tick follows from the one before under vanilla's rules; the cheat's doesn't.
- **Server-applied velocity is handled.** Knockback and slime bounces produced no fly, speed or ground-spoof flags.
- **Detection time grows with latency only where it must.** Ground spoof waits one extra round trip; hitbox and multi-target wait the network delay itself. Fly, speed, timer and fast break count client ticks, so they are almost independent of latency.
- **The `multi_target` premise holds.** With four clicks queued in one tick, the real vanilla client sent all four attacks at the same entity, in every profile.
- **The tests can't prove the absence of false positives** on real servers, with real players, terrain, mobs and other mods. That is why these checks are marked experimental and MejtaAC starts in `DRY_RUN`.

The suite writes this table to `build/run/clientGameTest/mejtaac-stage4-latency.md` on every run. Since 0.5.0 its legitimate-play scenarios also require zero `movement.jump` and `vehicle.boat_fly` flags.

### Stage 5 latency tests: jump height and boats

`MejtaAcStage5GameTest` was added after a field test with a hack client: Fly, KillAura, Reach, Hitbox, Not Looking and FakeLag were flagged, but **BoatFly and Jump Height were not**. It uses the same five network profiles. For each one, a real non-op client first plays legitimately in the situations most likely to cause false positives, and **all six movement checks must stay silent**:

- **Jumping:** ten jumps in place, with a 1.5 s network freeze in the middle in the jitter profile. The test asserts the vanilla height of 1.252 blocks.
- **Steps:** walking with jump held over a full block, a slab and stairs, then up a slab without jumping.
- **Jump Boost II:** held jumping, while the server removes the effect mid-jump. The client keeps it for a round trip, and in the jitter profile the network is also frozen.
- **Honey, and a bed bounce** from 9 blocks (asserted to bounce).
- **Teleports** 3 blocks up and 6 blocks sideways, like ender pearls.
- **A boat appearing under the player**, who then jumps around it ("dropping a boat").
- **Boats driven off a 6-block ledge** onto stone, slime (several bounces) and water. In the jitter profile the network freezes mid-fall. Each time the player leaves the boat and jumps right away.
- **A boat launched by a bubble column** (asserted to form and to lift the boat).

Then the client cheats, and both checks must catch it:
- **High jump:** take-off at 0.75 instead of 0.42 blocks/tick, five times.
- **Boat fly:** hovering, and climbing at 0.2 blocks/tick.

**Results** (2026-10-18, MejtaAC 0.5.0 on MejtaCore 1.2.0, one full run: Stage 5 110/110, Stage 4 279/279, Stage 3 68/68, unit tests 48/48; the table is written to `build/run/clientGameTest/mejtaac-stage5-latency.md`):

| Profile | Measured RTT | Legitimate-play flags (6 movement checks) | High jump 0.75: flagged / first / HIGH | Boat hover: first / HIGH | Boat climb 0.2: first / HIGH |
|---|---|---|---|---|---|
| 0 ms | 32 ms | **0** | 5/5, 0.05 s, 3.0 s | 0.65 s / 1.30 s | 0.65 s / 1.30 s |
| 150 ms | 153 ms | **0** | 5/5, 0.10 s, 3.1 s | 0.70 s / 1.35 s | 0.70 s / 1.35 s |
| 300 ms | 303 ms | **0** | 5/5, 0.15 s, 3.2 s | 0.75 s / 1.40 s | 0.75 s / 1.40 s |
| 500 ms | 502 ms | **0** | 5/5, 0.25 s, 3.3 s | 0.85 s / 1.50 s | 0.85 s / 1.50 s |
| 300 ms ±80 ms + 1.5 s freezes | 320 ms | **0** | 5/5, 0.20 s, 3.2 s | 0.80 s / 1.45 s | 0.80 s / 1.45 s |

What the results show:
- **Every high jump is flagged, but enforcement waits.** The first two jumps are MEDIUM and HIGH comes from the third, about 3 s in. A single odd take-off never leads to a kick.
- **The boat model is exact.** The movement trace of a boat bouncing seven times on slime matched the predicted `dy − 0.04` to every printed digit, and every landing was recognised. That is why the 0.03 tolerance is enough.
- **Fake lag doesn't create flags.** Freezes in the middle of a boat fall and of Jump Boost jumping produced none. Detection times grow only by the network delay.
- **Not yet proven:**
  - In one early development run, a boat's first slime bounce was flagged **once at MEDIUM**: the landing tick wasn't recognised. It didn't happen again in five profiles with per-tick tracing on (`AntiCheat.traceMovement`).
  - MEDIUM never enforces by default, but this is a known open question. A legitimate field session with boats is the next step.

## Roadmap

- **Next checks:**
  - Aim analysis for kill auras that do send rotations. This needs statistics over many attacks, so it is more prone to false positives and will start at MEDIUM.
  - Fast place, and scaffold-style building patterns.
  - Inventory actions while moving.
  - Other vehicles: horse, llama and camel jump and speed, striders, and boat speed. `vehicle.boat_fly` covers vertical boat movement only.
  - Elytra flight without fireworks.
- **Field testing:** run the experimental checks in `DRY_RUN` on a real server (see `../server`) and review their records.
- **MejtaCore candidates** (useful to every addon): `/mejtaadmin config get/set` for dedicated servers, persistent notification mutes, and an "experimental" flag on settings.
