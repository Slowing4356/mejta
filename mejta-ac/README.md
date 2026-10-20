# MejtaAC

MejtaAC (mod ID `mejtaac`) is a server-side anti-cheat for Fabric servers. It checks movement, combat, block interaction and vehicle packets against vanilla's own rules, compensates for each player's latency, and reports what it finds to staff. Players need no client mod. MejtaAC is an addon for **MejtaCore** and uses its settings, permissions, commands, notifications and record storage.

| | |
|---|---|
| Version | 1.1.1 (see [CHANGELOG.md](CHANGELOG.md)) |
| Minecraft | 26.1.2, Fabric Loader 0.19.5+, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.3.0 or newer, below 2.0.0 (API version 4). Built and tested against MejtaCore 1.4.0, which is recommended. |
| Other dependencies | None |

## Installing

Put MejtaCore, Fabric API and `mejtaac-1.1.1.jar` in the **server's** `mods/` folder and start the server.

| | Dedicated server | Player's client |
|---|---|---|
| MejtaAC | Required | Not needed. All checks run on the server, and vanilla clients can join. |
| MejtaCore | Required | Optional. Only needed for the client-side `/mejta` menu. |
| Fabric API | Required | Only if the client has MejtaCore. |

In single-player, MejtaAC runs on the integrated server, so install everything on the client.

MejtaAC starts in **`DRY_RUN`** mode: it detects, alerts and records, but never kicks. Review the alerts and records first. When you trust them, set `enforcement.mode` to `ENFORCE`.

> Operators of level 4 have `mejtaac.bypass` by default and are never checked. To test MejtaAC on yourself, use a non-operator account or set `exemptions.bypass_permission` to `false`.

## How it works

1. **Detect.** A check looks at what the client sent, before vanilla clamps or corrects it. A suspicious input becomes a *flag* with a reason, a confidence (`LOW`, `MEDIUM`, `HIGH` or `CERTAIN`) and the measurements behind it.
2. **Count.** Each flag adds to the player's violation level (VL) for that check. VLs decay over time (`violations.decay_per_minute`) and are cleared when the player disconnects. Players with the bypass permission are skipped.
3. **Decide.** A kick needs both:
   - a flag at or above `enforcement.min_confidence` (default `HIGH`), and
   - the check's VL from such flags reaching its `kick_vl`.

   Flags below the minimum confidence still show in alerts, records and `vl`, but never count towards a kick. One weak or ambiguous signal never punishes anyone. A kick decision resets that check's VL.
4. **Act.** In `ENFORCE` the player is kicked with `enforcement.kick_message`. In `DRY_RUN` MejtaAC records and alerts "would kick" instead. In `OFF` nothing happens. Every decision is written to the enforcement audit log.

**Latency compensation (tick sync).** Movement and combat checks must judge a player by what *their* client showed, not by the server's current state. At the end of each server tick, MejtaAC sends every player a tiny ping that the vanilla client answers in order. When the answer for tick N arrives, MejtaAC knows that client had seen tick N, and it gets a round-trip time for every tick. Reach and hitbox then measure against every position the target could have had on the attacker's screen. Movement checks wait until the client has confirmed knockback, teleports and other server changes before they judge it again. The client's own tick-end packet gives exact client tick boundaries. Pings are only sent while a check that needs them is enabled.

## Mitigation (experimental)

A kick punishes. Mitigation only undoes what the cheat just did. It is **off by default** (`enforcement.mitigation`: `OFF`).

With `ON`, every single flag at or above `enforcement.min_confidence` is undone, in `DRY_RUN` and in `ENFORCE`. It needs no VL and does not change when kicks happen. It never happens in `OFF` mode, for players with the bypass permission, or for a flag that kicks the player anyway.

| Checks | Action | What happens |
|---|---|---|
| `movement.fly`, `movement.speed`, `movement.jump`, `movement.elytra` | `setback` | The player is teleported back to the last safe position, with vanilla's own "moved wrongly" correction. |
| `combat.reach`, `combat.multi_target` | `cancel_attack` | The attack is dropped before vanilla processes it: no damage, no knockback. |
| `combat.hitbox` | `block_attacks` | The hit is judged after vanilla has processed it, so the player's attacks are cancelled for the next 10 client ticks instead. |
| `interaction.block_reach`, `interaction.fast_break` | `cancel_action` | The block action is dropped, and the client gets the real block states so no ghost block stays. |

Setbacks never go into a block, across dimensions or farther than 64 blocks. There is at most one every 0.25 s, and none while a teleport is unconfirmed. Safe positions are only remembered while mitigation is on.

Not mitigated:
- Flags below the minimum confidence. With the default `HIGH`, this includes every `movement.elytra` and `vehicle.horse` flag.
- `protocol.invalid_move` (vanilla already clamps or disconnects), `movement.timer`, `movement.ground_spoof`, `vehicle.boat_fly` and `vehicle.horse`.

Mitigated flags are marked in records (`"mitigation": "<action>"`), alerts (`[mitigated: <action>]`) and the log.

## Checks

| Check | Catches | Highest confidence | Status |
|---|---|---|---|
| `protocol.invalid_move` | Pitch beyond ±90°, NaN or infinite values, positions out of world bounds | `CERTAIN` | Stable |
| `combat.multi_target` | Two different entities attacked in one client tick | `CERTAIN` | Stable |
| `movement.timer` | Clients that run more ticks than real time allows (timer) | `HIGH` | Experimental |
| `movement.ground_spoof` | Claiming to stand on the ground in mid-air (also most NoFall) | `HIGH` | Experimental |
| `movement.fly` | Hovering, flying up, falling slower than gravity | `HIGH` | Experimental |
| `movement.jump` | Jumps higher than jump strength and Jump Boost allow | `HIGH` | Experimental |
| `movement.speed` | Horizontal movement faster than vanilla physics (speed, strafe, bunny hop) | `HIGH` | Experimental |
| `movement.elytra` | Elytra flight that ignores glide physics, boosting without a rocket | `MEDIUM` | Experimental |
| `combat.reach` | Attacks beyond the held weapon's range | `HIGH` | Experimental |
| `combat.hitbox` | Attacks on entities the player was not looking at | `HIGH` | Experimental |
| `interaction.block_reach` | Breaking, using or placing against blocks beyond interaction range | `HIGH` | Experimental |
| `interaction.fast_break` | Blocks broken faster than tool, enchantments and effects allow | `HIGH` | Experimental |
| `vehicle.boat_fly` | Boats and rafts that hover, rise or fall slower than gravity | `HIGH` | Experimental |
| `vehicle.horse` | Horses, donkeys, mules, skeleton and zombie horses and camels that move faster, jump higher or fly | `MEDIUM` | Experimental |

Experimental checks have only been tested with the automated suites, not on a busy real server. Run them in `DRY_RUN` and review their flags before you enforce. Checks that reach only `MEDIUM` never kick or mitigate unless you lower `enforcement.min_confidence`.

### Notes for admins

- **`protocol.invalid_move`:** fully deterministic, so lag cannot cause a flag. Yaw is not range-checked, because the vanilla client sends it unwrapped.
- **`combat.multi_target`:** the vanilla client resolves all clicks in a tick against one target. Spear hits on several entities use a different packet and are not counted.
- **`movement.timer`:** absorbs jitter, network stalls and the catch-up burst that follows them. A 2× timer is caught in about 1.5 s, a 1.3× timer in about 5 s. Speed-ups below about 1.05× are tolerated by design. Not run for the single-player host, and paused while `/tick` changes the tick rate. Tune with `max_ahead_ms`.
- **`movement.ground_spoof`:** waits until the client has seen the ground disappear, plus a margin based on its recent round-trip time. Exempt while riding, flying, gliding, swimming, climbing, sleeping, and similar.
- **`movement.fly`, `movement.jump`, `vehicle.boat_fly`:** predict each tick from the previous one under vanilla gravity. They reset on ground, collisions, liquids, climbing, cobwebs and similar blocks, pistons, vehicles and server-applied velocity. Jumps, slime and bed bounces never flag. A too-high jump is `MEDIUM`. From the third one in a row (each within 30 s of the last), a jump more than 0.1 blocks per tick too high is `HIGH`.
- **`movement.speed`:** uses the player's real movement-speed attribute (sprint, Speed, Soul Speed) and the most slippery recent block. It is not judged in liquids, vehicles, during flight, elytra or riptide, near pistons, or until server velocity is confirmed. After an elytra glide it also skips the landing until the client has confirmed the glide ended, plus 5 ticks.
- **`movement.elytra`:** follows vanilla's glide physics tick by tick. Firework rockets near the player switch the prediction off until the client has seen them gone. Flags are `MEDIUM` only. `tolerance` widens the per-tick margin.
- **`combat.reach`:** uses vanilla's own range for the held item, so spears, custom weapons and creative reach are respected. Up to `tolerance` (0.1) over: no flag. Up to 0.5 over: `MEDIUM`. More: `HIGH`. Without recent tick sync, flags are at most `MEDIUM`.
- **`combat.hitbox`:** kill auras that send matching rotations pass by design. A clear miss is `HIGH` only when the client's tick is confirmed.
- **`combat.reach`, `combat.hitbox`, `interaction.block_reach`:** not judged after a teleport or respawn until the client has confirmed it.
- **`interaction.block_reach`:** over the range by more than `tolerance`: `MEDIUM`; by more than 0.5: `HIGH`. Far blocks are never looked up, so a client cannot make the server load chunks.
- **`interaction.fast_break`:** counts the client's own ticks, so latency cannot shorten the time. Two ticks of slack. Finishing well under 70 % of the required time is `HIGH`. Creative mode and instant-break blocks are skipped.
- **`vehicle.horse`:** uses the mount's own speed and jump-strength attributes, including effects. `speed_tolerance` covers entities pushing the mount. Striders, pigs, happy ghasts and nautiluses are not checked.

## Configuration

Settings live in `config/mejta/mejtaac.json` and are managed by MejtaCore. Invalid values fall back to the default with a warning. Change them:

- **In-game, any client:** `/mejtaadmin config menu mejtaac` (MejtaCore 1.4.0+). Needs `mejtacore.config.edit`.
- **In-game, with MejtaCore on the client:** **/mejta → Configuration → Server Settings → MejtaAC**.
- **By command (also console):** `/mejtaadmin config set mejtaac enforcement.mode ENFORCE`, `/mejtaadmin config get mejtaac`.
- **By hand:** edit the file, then run `/mejtaadmin config reload`.

Changes apply immediately. Only the `records.*` size and retention limits need a restart. Disabling a check clears its VLs.

| Key | Default | Range | Meaning |
|---|---|---|---|
| `enabled` | `true` | | Master switch. Off: no check runs. |
| `log.level` | `NORMAL` | `QUIET`, `NORMAL`, `VERBOSE`, `DEBUG` | `QUIET`: enforcement only. `NORMAL`: flags, rate-limited. `VERBOSE`: adds measurements and latency. `DEBUG`: every flag plus exemptions, for false-positive hunting. |
| `violations.decay_per_minute` | `2.0` | 0–600 | VL removed per minute, per check. `0`: VLs reset only on disconnect or after a kick decision. |
| `alerts.enabled` | `true` | | Publish staff alerts. |
| `alerts.min_vl` | `1.0` | 0–10000 | Alert only once the VL for a check reaches this. |
| `alerts.cooldown_seconds` | `5` | 0–3600 | Minimum time between alerts for the same player and check. |
| `alerts.enforcement` | `true` | | Publish every kick and would-be kick, even when `alerts.enabled` is off. |
| `enforcement.mode` | `DRY_RUN` | `OFF`, `DRY_RUN`, `ENFORCE` | What happens when a threshold is reached. |
| `enforcement.min_confidence` | `HIGH` | `LOW`, `MEDIUM`, `HIGH`, `CERTAIN` | Flags below this never count towards a kick and are never mitigated. |
| `enforcement.mitigation` | `OFF` | `OFF`, `ON` | Experimental. Undo the action behind every confident flag. See [Mitigation](#mitigation-experimental). |
| `enforcement.kick_message` | `Disconnected by anti-cheat.` | 1–256 characters | Shown to kicked players. |
| `exemptions.bypass_permission` | `true` | | Skip players with `mejtaac.bypass`. |
| `records.enabled` | `true` | | Write violation and enforcement records. |
| `records.max_file_mb` | `5` | 1–256 | Rotate a record file at this size in MiB. Restart to apply. |
| `records.max_files` | `5` | 1–100 | Files kept per log. Restart to apply. |
| `records.retention_days` | `30` | 0–3650 | Delete rotated files older than this. `0`: keep until `max_files`. Restart to apply. |

Every check has `checks.<id>.enabled` (default `true`) and `checks.<id>.kick_vl` (range 1–10000). `HIGH` and `CERTAIN` flags add 1 VL, `MEDIUM` flags add 0.5.

| Check | `kick_vl` default | Extra settings |
|---|---|---|
| `protocol.invalid_move` | `5.0` | |
| `combat.multi_target` | `3.0` | |
| `movement.timer` | `5.0` | `max_ahead_ms`: `500` (200–5000). How far ahead of real time a client may get, beyond the jitter and lag allowances. |
| `movement.ground_spoof` | `5.0` | |
| `movement.fly` | `5.0` | |
| `movement.jump` | `5.0` | |
| `movement.speed` | `5.0` | |
| `movement.elytra` | `5.0` | `tolerance`: `0.01` (0–0.5). Blocks per tick allowed beyond the glide prediction, plus 0.5 % of the speed. |
| `combat.reach` | `5.0` | `tolerance`: `0.1` (0–3). Extra blocks beyond the weapon's range. |
| `combat.hitbox` | `5.0` | |
| `interaction.block_reach` | `5.0` | `tolerance`: `0.1` (0–3). Extra blocks beyond the interaction range. |
| `interaction.fast_break` | `5.0` | |
| `vehicle.boat_fly` | `5.0` | |
| `vehicle.horse` | `5.0` | `speed_tolerance`: `0.06` (0–1). Blocks per tick a mount may exceed its speed limit. |

For example, the reach tolerance key is `checks.combat.reach.tolerance`.

## Permissions

Nodes use Minecraft operator levels through MejtaCore. Change the level of any node in `config/mejta/mejtacore.permissions.json`.

| Node | Default level | Grants |
|---|---|---|
| `mejtaac.alerts` | Moderators (1) | Alerts in chat; the `status`, `alerts`, `vl` and `history` commands |
| `mejtaac.admin` | Gamemasters (2) | The `reset` command |
| `mejtaac.bypass` | Owners (4) | Exemption from all checks, while `exemptions.bypass_permission` is on |

## Commands

All commands are under `/mejtaadmin mejtaac`.

| Command | Permission | What it does |
|---|---|---|
| `status` | `mejtaac.alerts` | Mode, minimum confidence, mitigation, alert and log settings, each check's state, and how many players have violations |
| `alerts` | `mejtaac.alerts` | Hide or show anti-cheat alerts and kick notices in your own chat. Kept across restarts. |
| `vl <player>` | `mejtaac.alerts` | The player's current violation levels |
| `history <name> [1-50]` | `mejtaac.alerts` | Recent violation records, newest first (10 by default). Works for offline players. |
| `reset <player>` | `mejtaac.admin` | Clear the player's violation levels |

## Logs, records and alerts

| Output | Where | Contents |
|---|---|---|
| Server log | Logger `MejtaCore/mejtaac`, detail set by `log.level` | Flags and decisions |
| Staff alerts | Notification channel `mejtaac.alert`, shown in chat to `mejtaac.alerts` holders | Check, reason, VL, measurements, ping, world |
| Enforcement notices | Notification channel `mejtaac.enforcement` | Every kick and would-be kick, with everything needed to decide on a ban |
| Violation records | `mejta-data/mejtaac/violations.jsonl` | One JSON object per flag (read by `history`) |
| Enforcement audit | `mejta-data/mejtaac/enforcement.jsonl` | Every kick and dry-run "would kick" |

Records and log lines are limited to one per second per player and check. The next one says how many similar flags were skipped (`suppressed`). Alerts use `alerts.cooldown_seconds`. Example record:

```json
{"type":"violation","player":"Steve","uuid":"…","check":"protocol.invalid_move","reason":"impossible_pitch","vl":3.0,"action":"NONE","confidence":"CERTAIN","vl_added":1.0,"measurements":{"yaw":12.5,"pitch":120.0},"ping_ms":34,"world":"minecraft:overworld","suppressed":0,"ts":"2026-10-18T04:31:16Z"}
```

Records and alerts hold the player's name and UUID, never IP addresses. Disk use is capped by the `records.*` settings.

## Integrations

MejtaAC talks to other mods only through MejtaCore, so none of these are required.

- **MejtaDiscord** forwards `mejtaac.alert` and `mejtaac.enforcement` to Discord webhooks. Nothing leaves the server unless it is installed and configured. See its README for routing.
- **MejtaCore's notification archive** (`mejta-data/mejtacore/notifications.jsonl`) keeps a local copy of every alert and kick notice, with or without Discord.
- **MejtaPerf:** `/mperf metrics mejtaac` shows what each MejtaAC hook costs per tick (timers `mejtaac:hook.*`).

## Building and testing

```bash
cd ../mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.4.0 into ~/.m2
cd ../mejta-ac && ./gradlew build                 # build/libs/mejtaac-1.1.1.jar, runs unit tests
./gradlew test                                     # unit tests only (JUnit 6)
./gradlew runClientGameTest                        # end-to-end tests with a real client (long)
```

**Unit tests** (`src/test`) feed every check legitimate inputs that must not flag and cheat inputs that must, using vanilla's physics. They also cover violation levels, rate limits, every enforcement decision and every mitigation decision.

**Client game tests** (`src/gametest`) start a real Minecraft client. Each step logs `[MejtaAcTest] PASS/FAIL`.

| Suite | What it verifies |
|---|---|
| `MejtaAcClientGameTest` | The pipeline, alerts, records, `DRY_RUN`, bypass, live config changes and all commands in single-player; kicks in `ENFORCE` on a dedicated server with a non-op player |
| `MejtaAcStage4GameTest` | Timer, ground spoof, fly, speed, reach, hitbox, multi-target, block reach and fast break under latency |
| `MejtaAcStage5GameTest` | Jump and boat fly under latency |
| `MejtaAcStage6GameTest` | Elytra, horse and camel riding, and every mitigation action, under latency |

The latency suites run a non-op client on a dedicated server through a test-only network delay: 0, 150, 300 and 500 ms, and 300 ms with jitter and 1.5 s freezes. In each profile, legitimate play with real input must produce **zero** flags, and crafted cheats must be caught. Each suite writes a results table to `build/run/clientGameTest/mejtaac-stage<N>-latency.md`.

## Limitations

- **No real hack clients in the test suites.** The tests craft cheat packets or force the client's movement. Real cheat clients may behave differently.
- **No field data yet.** Experimental checks have not run on a busy server with real players, terrain and mods. Keep them in `DRY_RUN` until you have reviewed their flags.
- **Not detected:** kill auras that send matching rotations, aim patterns, fast place and scaffold, inventory actions while moving, striders, pigs, happy ghasts, nautiluses, boat speed and vehicle timer, elytra state spam (no-fall).
- **Single-player:** the host is not checked by `movement.timer`.

## Roadmap

- Aim analysis for kill auras that send rotations.
- Fast place and scaffold patterns; inventory actions while moving.
- More vehicles: striders, pigs, happy ghasts, boat speed and vehicle timer.
- More mitigation: vehicle setbacks, fall damage for ground spoofing, dropping a timer's excess movement.
- Raise `movement.elytra` and `vehicle.horse` to `HIGH` once field data shows they are quiet.
- Mark experimental settings with MejtaCore's experimental flag instead of the `[Experimental]` text.
