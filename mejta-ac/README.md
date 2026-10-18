# MejtaAC

MejtaAC (mod ID `mejtaac`) is a server-side anti-cheat addon for **MejtaCore**. It uses MejtaCore's configuration, permissions, server commands, notifications and record storage instead of building its own.

| | |
|---|---|
| Version | 0.2.0 (Stage 4a: protocol, timer, ground spoof, reach and multi-target checks) |
| Minecraft | 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | **MejtaCore 1.1.0 or newer, below 2.0.0** (API version 2) |
| Third-party libraries | None |

## Installing

Put `mejtacore-1.1.0.jar`, `mejtaac-0.2.0.jar` and Fabric API in the **server's** `mods/` folder. Players don't need any mods. In single-player, MejtaAC runs on the integrated server.

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

### `combat.multi_target` (stable)

This check flags attacks on two different entities within one client tick. The vanilla client resolves every queued attack click in a tick against the same crosshair target. The test suite verifies this with real rapid clicking. Spear hits on several entities use a different packet and aren't counted.

- **Latency:** not timing-based, so latency can't cause a flag.
- **Flags:** CERTAIN confidence.

## Configuration

Settings are stored in `config/mejta/mejtaac.json` and managed by MejtaCore: values are validated, and invalid ones fall back to defaults with a warning. In single-player you can also edit them under **/mejta → Configuration → MejtaAC**. On a dedicated server, edit the file and run `/mejtaadmin config reload`.

Changes apply immediately, including to checks that are already running. The exception is the `records.*` size limits, which apply after a restart. Disabling a check also clears its violation levels.

| Key | Type | Default | Range | Purpose |
|---|---|---|---|---|
| `enabled` | boolean | `true` | | Master switch. When off, no check runs. |
| `log.level` | enum | `NORMAL` | `QUIET`, `NORMAL`, `VERBOSE`, `DEBUG` | Server log detail. `QUIET`: enforcement only. `NORMAL`: flags (rate-limited). `VERBOSE`: adds measurements, ping and world. `DEBUG`: every flag without rate limiting, plus exemptions. Use `DEBUG` to investigate false positives. |
| `violations.decay_per_minute` | decimal | `2.0` | 0–600 | VL removed per minute, per check. `0` means VLs only reset on disconnect or after an enforcement decision. |
| `alerts.enabled` | boolean | `true` | | Publish staff alerts. |
| `alerts.min_vl` | decimal | `1.0` | 0–10000 | Alert only once the player's VL for the check reaches this value. |
| `alerts.cooldown_seconds` | integer | `5` | 0–3600 | Minimum time between alerts for the same player and check. |
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
| Enforcement audit | `mejta-data/mejtaac/enforcement.jsonl` and channel `mejtaac.enforcement` | Every kick and dry-run "would kick" |
| External notifications | Providers registered with MejtaCore (none built in) | e.g. Discord, later |

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
cd ../core && ./gradlew publishToMavenLocal    # MejtaCore 1.1.0 into ~/.m2
cd ../ac && ./gradlew build                    # build/libs/mejtaac-0.2.0.jar
./gradlew runClientGameTest                    # end-to-end tests
```

The game test (`src/gametest`) logs `[MejtaAcTest] PASS/FAIL` for each check:
- **Pure logic, legitimate inputs:** normal positions, the world border, pitch exactly ±90, huge unwrapped yaw, and fields the client didn't send. None may flag.
- **Pure logic, suspicious inputs:** each reason, measurements, confidence; VL accumulation and decay; rate limiting; every enforcement decision (including "a low-confidence flag never enforces").
- **Single-player, real client:** walking, jumping and turning more than two full circles (no flags); crafted pitch-120 packets (VL, staff alert and its cooldown, rate-limited record with measurements, no IP address); DRY_RUN would-kick audit; bypass permission and its setting; live config changes and a reload from disk; all commands, including muting alerts.
- **Dedicated server, non-op player:** ENFORCE kicks at the threshold with the configured message and writes an audit record; state is cleaned up on disconnect. A NaN rotation is recorded before vanilla's own disconnect.

### Stage 4 latency tests

The suite `MejtaAcStage4GameTest` runs a real client on an in-process dedicated server as a non-op. The client goes through a test-only Netty handler that adds delay and jitter in both directions and can freeze the connection; it doesn't reorder packets, just as TCP doesn't. Netty already ships with Minecraft. For each profile the suite does three things:

1. **Checks the latency measurement:** the RTT measured from pongs must match the simulated RTT.
2. **Plays legitimately and requires zero flags from all four checks:**
   - idling, walking and jumping, plus a 1.5 s freeze in the jitter profile;
   - hitting a target that moves **away** at 0.3 blocks/tick, with real crosshair attacks whenever the client sees it within 2.8 blocks;
   - four clicks queued in one tick, which also verifies the premise behind `multi_target`;
   - the floor under the player being removed.
3. **Sends crafted cheats and requires detection:**
   - attacks at 4.2 blocks (HIGH), 3.3 blocks (MEDIUM only) and 3.05 blocks (no flag);
   - two targets attacked in one tick;
   - extra tick-end packets at 2×, 1.3× and 1.05× (the last must stay undetected);
   - ground spoofing during a 40-block fall.

The moving-target scenario is the key compensation test. The suite also records how far away the target was on the server at the moment each legitimate hit arrived. That is what a check without compensation would measure, and for profiles of 300 ms and above it must exceed 3.6 blocks.

**Results** (2026-10-18; 97 checks passed, 0 failed; Stage 3 suite 68/68):

| Profile | Measured RTT | Legitimate-play flags | Timer: time to detect | Ground spoof: time to detect | Reach (crafted hits) | Multi-target: time to detect |
|---|---|---|---|---|---|---|
| 0 ms | 0 ms | **0** (18 hits, up to 4.25 blocks uncompensated) | 2×: 1.45 s, 1.3×: 4.6 s, 1.05×: below tolerance | 0.40 s | 4.2: HIGH, 3.3: MEDIUM, 3.05: none | immediate |
| 150 ms | 175 ms | **0** (18 hits, up to 5.15 blocks) | 2×: 1.50 s, 1.3×: 4.7 s, 1.05×: below tolerance | 0.70 s | same | 0.10 s |
| 300 ms | 302 ms | **0** (12 hits, up to 5.75 blocks) | 2×: 1.60 s, 1.3×: 4.7 s, 1.05×: below tolerance | 1.00 s | same | 0.20 s |
| 500 ms | 502 ms | **0** (11 hits, up to 6.35 blocks) | 2×: 1.65 s, 1.3×: 4.9 s, 1.05×: below tolerance | 1.35 s | same | 0.30 s |
| 300 ms ±80 ms + 1.5 s freeze | 313 ms | **0** (11 hits, up to 6.35 blocks) | 2×: 1.60 s, 1.3×: 4.8 s, 1.05×: below tolerance | 1.05 s | same | 0.15 s |

What the results show:
- **Compensation is needed at any latency.** At 0 ms, legitimate hits on a target moving away still arrive when the server-side target is up to 4.25 blocks away. This comes from vanilla's entity update interval and client interpolation alone, and network latency adds to it (6.35 blocks at 500 ms). None of these hits was flagged.
- **Detection time grows with latency only where it must.** Ground spoof waits one extra round trip, and multi-target waits the network delay itself. Timer detection is almost independent of latency.
- **The `multi_target` premise holds.** With four clicks queued in one tick, the real vanilla client sent all four attacks at the same entity, in every profile.
- **The tests can't prove the absence of false positives** on real servers, with real players, terrain, mobs and other mods. That is why these checks are marked experimental and MejtaAC starts in `DRY_RUN`.

The suite writes this table to `build/run/clientGameTest/mejtaac-stage4-latency.md` on every run.

## Roadmap

- **Stage 4b:** `combat.hitbox` (the look direction misses the target), `movement.gravity`, `movement.speed` (a horizontal speed limit with allowances, starting at MEDIUM), and interaction checks. They reuse the tick sync and position history.
- **Stage 5:** checks for false positives under artificial latency, and per-world configuration if needed.
- **MejtaCore candidates** (useful to every addon): `/mejtaadmin config get/set` for dedicated servers, persistent notification mutes, and an "experimental" flag on settings.
