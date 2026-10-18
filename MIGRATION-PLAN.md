# Mejta ecosystem: migration plan

Status: **First release (2026-10-18): MejtaCore 1.3.0, MejtaAC 1.0.0, MejtaUtils 1.0.0, MejtaDiscord 1.0.0 and MejtaPerf 1.0.0.** M1 to M4 are done. MejtaAC 1.0.0 is the 0.5.0 check set plus the release-review fixes; M5's new checks are still open. Your decisions are recorded in section 8.

The integration design for MejtaUtils and MejtaDiscord, including the MejtaCore 1.3.0 API, is in [`INTEGRATION-DESIGN.md`](INTEGRATION-DESIGN.md). Your decisions and what was built are in its section 8.

This plan covers the upgrade of the existing MejtaCore and MejtaAC projects and the creation of the new addons. It records what exists today, what changes, what each change breaks (nothing so far), and what still needs your decision.

---

## 1. Milestone 1: what was done

| Step | Result |
|---|---|
| Inspect workspace | `vscode/minecraft/mejta/` contains `core/`, `ac/` and `server/`. No other Mejta directories exist anywhere under `almost_EVERYTHING/`. The parent `mejta/` is **not** a git repository, as required. |
| Inspect repositories | Both already had git repositories, created earlier in this project. Their history was **kept, not re-initialised**: core has 3 commits (1.1.0, line endings, 1.2.0) and ac has 4 (0.2.0, line endings, 0.3.0, 0.4.0). Both working trees were clean. |
| Secret and size scan | Tracked files contain no tokens, keys, passwords, webhooks or IP addresses; the only matches were documentation and the test permission `example.secret`. The largest tracked files are the 48 KB Gradle wrapper jars. `build/`, `.gradle/` and `run/` are ignored. |
| Backup | `vscode/minecraft/mejta-backups/2026-10-18_1331/`, outside the workspace so it can never be committed. Contents:<br>• `core.bundle` and `ac.bundle`: full git history.<br>• `mejta-worktree.zip`: 548 files, including `.git/`, sources and the server config and records. It excludes build output and the server world.<br>• `SHA256SUMS`.<br>**Verified:** both bundles were cloned into a temporary folder and their tree hashes match the live repositories exactly; the zip passed its integrity test. |
| Repository layout | The folders were renamed to match the future repository layout (`repo/mejta-core`, `repo/mejta-ac`, `repo/mejta-discord` …):<br>• `core/` → **`mejta-core/`**<br>• `ac/` → **`mejta-ac/`**<br>Path references in `mejta-ac/README.md`, `mejta-ac/gradle.properties`, `server/README.txt` and `server/update-mods.sh` were updated. Both projects rebuild from the new folders. |
| `.gitignore` | Added `.env`, `*.env`, `secrets/`, `*.pem`, `*.key`, `*.p12` and `mejta-data/` (violation records contain player names and UUIDs). |
| Commits | `mejta-core` ed3dac3 and `mejta-ac` 4075483 ("Rename project folder to match repository name; ignore local secrets"). The staged diffs were reviewed first. Nothing was pushed; there are no remotes. |
| New projects | `mejta-utils`, `mejta-discord` and `mejta-perf` exist and were released as 1.0.0 (see `INTEGRATION-DESIGN.md` section 8 and each README). `mejta-corpse` and `mejta-qol` **do not exist yet**; `mejta-logger` is not planned. Section 6 plans them. |

**Breaking changes so far:** only the folder names. Gradle, git and the jars don't depend on them. If VS Code still has `core/` or `ac/` open, reopen `mejta/mejta-core` and `mejta/mejta-ac`.

### Monorepo (your decision 2)

`mejta/` is now **one git repository** with each project as a subfolder. It will be hosted privately on GitHub.

- **Built with `git filter-branch`.** Each project's commits were rewritten into its subfolder, then the two histories were merged. **All 9 original commits are kept**, and `git log --follow mejta-ac/<file>` reaches back to MejtaAC 0.2.0.
- **Verified:** the `mejta-core/` and `mejta-ac/` trees in the monorepo are byte-identical (same tree hashes) to the two original repositories.
- **Old repositories archived:** the inner `.git` folders were moved to `mejta-backups/2026-10-18_1331/pre-monorepo/`, next to fresh, verified bundles.
- **Ignored:** `server/` (the test server is never committed) and the same secret and data rules as the projects.

---

## 2. Field test (your play session, 11:00–11:12 UTC)

**Settings:** MejtaAC 0.4.0, DRY_RUN, bypass off, survival. Nobody was kicked; the server logged 17 "would be kicked" decisions.

| Check | Flags | What the records show |
|---|---|---|
| `combat.reach` | 36 (27 HIGH) | Up to **5.88 blocks** against a 3.0 limit, RTT 3–10 ms, mostly in one fight (13:05–13:06 local time). |
| `combat.hitbox` | 21 (18 HIGH) | Aim off the hitbox by only 0.5–1.1°. Nearly every one coincides with a reach flag. |
| `movement.speed` | 27 (1 HIGH) | **13:02:27–13:02:53:** single-tick jumps of 2.0–5.6 blocks/tick against a 0.13 limit.<br>**13:09–13:11:** a steady 0.44 against 0.37. A boat had been given at 13:03. |
| `movement.fly` | 19 (7 HIGH) | dy = 0.0 ("hovering") for up to 57 ticks. **Vanilla itself logged "<player> moved wrongly!" twice at the same moments.** |
| `interaction.block_reach` | 1 MEDIUM | `use_on_block` at 4.75 blocks against 4.5. |
| Other five checks | 0 | |

**Confirmed by you: this session used a hack client on purpose.** These are **true detections**, and your module-by-module results were:

| Hack module | Result | Caught by |
|---|---|---|
| Fly | **Flagged** | `movement.fly` (hovering, HIGH within 2 s) |
| KillAura | **Flagged** (many) | `combat.reach`, `combat.hitbox` |
| Reach | **Flagged** | `combat.reach` (up to 5.88 blocks) |
| Hitbox | **Flagged** | `combat.hitbox` |
| Not looking | **Flagged** | `combat.hitbox` |
| FakeLag | **Flagged** (as you reported) | Not separable in the records: it was combined with combat modules, so the flags are reach and hitbox. Whether FakeLag *alone* is detected, without causing false positives on real lag, still needs testing. |
| **BoatFly** | **Missed** | Nothing checks vehicles yet. New check `vehicle.boat_fly` (M2). |
| **Jump height** | **Missed** | `movement.fly` treats every tick that starts on the ground as unknown, so it never judges a take-off. New check `movement.jump` (M2). |

**False-positive warnings you gave, each of which gets a dedicated legitimate-play test:**
- **Jump height:** stairs and slabs (stepping up 0.6 without jumping), jump boost, honey and soul sand, slime and bed bounces, bubble columns, knockback, levitation, pistons, and getting out of a boat.
- **Dropping boats:** placing a boat, falling while in a boat, a boat sliding off a ledge, boats on ice, boats in bubble columns and waterfalls, getting out over water, and the boat breaking under a passenger.
- **Fake lag:** legitimate lag spikes, packet bursts after a freeze, and jitter. These must never flag on their own. The existing jitter-plus-freeze latency profile becomes mandatory for every new check.

---

## 3. MejtaCore: inventory and upgrade (compatibility-preserving)

Current version 1.2.0, `API_VERSION = 3`, about 6,900 lines of Java. Tests: test-addon client gametest (144 checks).

### 3.1 Existing features

| Feature | Verdict | Reason / planned change |
|---|---|---|
| Addon registry and lifecycle (`MejtaAddon`, `AddonDefinition`, `AddonState`, failure isolation) | **Preserve** | Sound and used by MejtaAC. |
| Config system (`ConfigSpec`, `Setting`, validators, migrations, atomic JSON files, reload) | **Preserve, improve** | **Add** a real `experimental` flag and setting groups; today experimental status only appears in descriptions.<br>**Add** a `secret` setting type: masked in the UI, logs and `config get`, and **never sent to clients** by Server Settings. MejtaDiscord's bot token or webhook needs this; today Server Settings sends every value to permitted clients. |
| Remote config editing (Server Settings, `/mejtaadmin config`) | **Preserve** | Will honour `secret`. |
| Permissions (operator levels, `mejtacore.permissions.json`) | **Preserve, improve** | Fabric API 0.155.3 already ships `fabric-permission-api-v1` (it is in the server log). Bridging to it would give LuckPerms-style per-player nodes **without a new library**. This needs a check of that API first; operator levels stay as the fallback. |
| Notifications (`NotificationBus`, providers, in-game provider, mutes) | **Preserve, improve** | **Add:**<br>• Discord-ready structure: title, fields, severity colour, optional player UUID.<br>• Per-provider rate limit and retry.<br>• Mutes that survive restarts. |
| Record logs (JSONL, rotation, retention, NaN guard) | **Preserve, improve** | **Add** a bounded query API (filter by player, type and time) for `history` commands, MejtaLogger and MejtaDiscord. |
| Server commands `/mejtaadmin`, client `/mejta` menu and chest UI | **Preserve** | |
| Diagnostics and logging (`MejtaLog`, `Diagnostics`) | **Preserve** | |

**Nothing is removed or replaced.**

### 3.2 New core APIs (MejtaCore 1.3.0, `API_VERSION = 4`, additive only)

1. **Structured events.** A typed, in-process event bus, so addons never depend on each other.
   - Example: MejtaAC publishes `mejtaac:violation`, and MejtaLogger and MejtaDiscord subscribe.
   - Events say which thread they are delivered on: server thread, or async.
   - A failing listener is isolated, and listener time is measured.
2. **Bounded asynchronous processing.** One core-owned executor per addon. It has a queue limit, counts dropped tasks and shuts down cleanly. It replaces the ad-hoc threads in the notification bus and record logs.
3. **Persistence.** Per-addon and per-player data storage (corpses, homes, mutes) as atomic JSON files. A database would need a library (SQLite), so it stays out unless you approve it.
4. **Server scheduler.** Run code after N ticks or every N ticks, on the server thread.
5. **Metrics.** Timers and counters that addons can report (check cost per tick, queue sizes). MejtaPerf and the AC performance budget use them.

**Compatibility rule:** existing addons built against API 2 or 3 keep working unchanged. If anything must ever break, it gets a deprecation period, an adapter and a test that runs an old-API addon.

---

## 4. MejtaAC: inventory of existing checks and features

Current version 0.4.0, about 4,600 lines of Java. Tests:
- Stage 3 suite: 68 checks.
- Latency suite: 269 checks across 0, 150, 300 and 500 ms and a jitter-plus-freeze profile.

| Item | Verdict | Reason |
|---|---|---|
| Pipeline: detect → assess (VL, decay) → decide (OFF, DRY_RUN or ENFORCE, plus minimum confidence) → report → act | **Retain** | It keeps detection, confidence, alerts and enforcement separate, as the spec requires. |
| `AntiCheat.java` with one large `PlayerData` | **Redesign** | Every check's state lives in one class, which hurts maintainability. Split it into one processor per check with its own state, keeping the proven logic unchanged. |
| Enforcement (kick only) | **Redesign, extend** | Add **mitigation** actions: cancel the hit, set the player back, cancel the block break. They are safer than kicks and need no certainty. Defaults stay conservative. |
| Mixin hooks (moves, attacks, block actions, tick end, pong, outgoing velocity) | **Retain, improve** | They are correct and tested. Next steps:<br>• Route all hooks through one dispatcher.<br>• Measure the time spent per packet.<br>• Add a packet-rate guard. |
| `TickSync` (ping/pong transactions), `EntityHistory` | **Retain**; investigate `EntityHistory` | Lag compensation is proven in tests with **players**. The field reach flags may mean mobs behave differently (section 2). |
| ViolationTracker, Throttle, AcRecords, AlertPublisher, commands | **Retain** | When core events exist, records and alerts will also be published as events. |
| `protocol.invalid_move` | **Retain** | 0 field flags; tested. |
| `movement.timer` | **Retain** | 0 field flags; latency-tested. |
| `movement.ground_spoof` | **Retain** | 0 field flags. |
| `movement.fly` | **Retain, extend** | Caught the hack client's fly in the field (19 flags, HIGH within 2 s). It missed **jump height**, which the new `movement.jump` check covers. Still to add: mob, boat and scaffolding legitimate-play tests. |
| `movement.speed` | **Retain** | Caught the speed and teleport bursts in the field. Still to add: a legitimate boat-exit and teleport test, to be sure resets are correct. |
| `combat.reach` | **Retain, extend tests** | Caught 3.1–5.9-block reach in the field. Its tests only fight **players**, so add mob combat (zombie, spider, skeleton) to the legitimate-play corpus. |
| `combat.hitbox` | **Retain, tune** | Caught the cheat's aim in the field, mostly alongside reach. Re-check the 0.1-block inflation against rotation precision. |
| `combat.multi_target` | **Retain** | 0 field flags. |
| `interaction.block_reach` | **Retain, tune** | One 4.75/4.5 flag on `use_on_block`. Decide whether placement should follow vanilla's +1 allowance at MEDIUM. |
| `interaction.fast_break` | **Retain** | 0 field flags. |

**Nothing is removed.** Every existing config key stays stable. New checks only add keys. A key rename would bump the spec version and ship with a migration.

### 4.1 Threat model: attack classes and new detection modules

**Principles:**
- The server is authoritative.
- One weak signal never punishes.
- Every new check starts as `experimental`, MEDIUM, in DRY_RUN.
- A check counts as implemented only when it is wired to the real packet or event path and has a legitimate-play test **and** a cheat-simulation test, under latency.

| Class | Attacks | Planned detection | Note |
|---|---|---|---|
| **Vehicles** | Boat fly, boat speed (including on ice), horse/llama/strider speed and jump, vehicle timer, steering spoof | Vehicle physics prediction on `ServerboundMoveVehiclePacket`, vehicle timer, buoyancy and gravity rules per vehicle type | Fly and speed skip vehicles today; this is the biggest gap. |
| **Elytra** | Elytra fly without fireworks, firework boost abuse, glide speed, START_FALL_FLYING spam (no-fall) | Glide physics prediction, firework accounting, toggle rate | `movement.fly` currently exempts gliding. |
| **Movement** | NoFall, step, jesus (water walking), spider (wall climb), phase/noclip, anti-knockback (velocity), no-slow, blink | • Fall-distance accounting<br>• Step height<br>• Fluid surface rules<br>• Climbing rules<br>• Collision validation<br>• Knockback compliance after the client acknowledges it<br>• Item-use slowdown<br>• Timer and blink correlation | |
| **Combat** | Kill aura (aim statistics, rotation snapping), auto-clicker, criticals, hits through walls, fast end-crystal placement | • Rotation statistics (needs many samples, MEDIUM)<br>• Line of sight against blocks<br>• Criticals vs. real fall state<br>• CPS and cooldown abuse | |
| **Interaction** | Fast place, scaffold, nuker, interacting through walls, fast use/eat | Place rate, placement under the player with impossible rotations, multi-break, line of sight, use duration | |
| **Inventory** | Clicks while moving or sprinting, auto-totem/auto-armor speed, invalid slots, click spam, crafting packet abuse | Click timing, movement state during clicks, slot and container validation, per-type rate limits | |
| **Packet abuse** | Packet floods, oversized or invalid payloads, NaN/∞ (already covered), never-answered pings ("disablers"), out-of-order transactions | • Per-type rate budgets<br>• Transaction timeout (a ping unanswered for N seconds while other packets keep arriving)<br>• Sequence validation | |

**Performance budget:** every check reports its cost through core metrics. The target is under 0.05 ms of server-thread time per player per tick. Async work only goes to bounded queues: records, notifications, statistics.

---

## 5. Test strategy

- **Unit tests for pure check logic.** Today every test is a gametest, which is slow and coarse. This needs **JUnit 5 as a test-only dependency** (decision 3).
- **Gametests**, with real vanilla clients under the existing latency profiles:
  - Legitimate-play corpus, expanded with mob combat, boats, horses, elytra, ladders, scaffolding, powder snow, bubble columns, ice, teleports and ender pearls.
  - Cheat simulations using test-only mixins, one per attack class in 4.1.
- **Field replay.** A debug option records a short, anonymised movement and attack trace around each flag, so false positives seen on the real server become regression tests.
- **Performance.** A gametest with many simulated players that asserts each check's tick cost.

---

## 6. New projects (your decision 4)

None exists yet. Each will be a subfolder of the monorepo with its own Gradle project, README and version, and will integrate **only** through MejtaCore's public API.

| Project | Purpose | Runs on | Core APIs used |
|---|---|---|---|
| `mejta-perf` | **Spark-style profiler for the Mejta ecosystem.** It is recommended but optional: nothing requires it, and core only exposes the metrics it reads.<br>• TPS/MSPT<br>• Per-addon and per-check cost<br>• Async queue depths<br>• Sampling profiler<br>• `/mejtaadmin perf` and a report export<br>Its findings drive optimisation of every Mejta mod: moving work off the server thread and cutting allocations. | Server (client view optional) | Metrics, async executor, commands |
| `mejta-utils` | **Admin commands:** `/invsee` (and ender chest), `/vanish`, `/freeze`, and others to be listed with you. | Server | Commands, permissions, persistence (vanish state) |
| `mejta-qol` | **Player quality-of-life commands:** `/tpa`, `/homes`, and others in future. For now a skeleton, so the commands have a home. | Server | Commands, permissions, persistence, scheduler (teleport warm-up) |
| `mejta-corpse` | **Corpses holding a dead player's items:** owner protection, timeout, and what happens when it expires. | Server (client optional) | Persistence, scheduler, permissions, config |
| `mejta-discord` | **Sends MejtaCore notifications to Discord.** Webhook first, using Java's built-in `HttpClient`, so no new library. **Each notification channel can be routed to its own Discord channel**: for example `mejtaac.enforcement` (kicks, with everything needed to decide on a ban) to `#ac-kicks`, `mejtaac.alert` to `#ac-alerts`, `mejtacore.config` to `#staff-log`, plus a default for channels without a route. Webhook URLs are `secret` settings. Delivery is rate-limited per Discord channel and retried; nothing that fails to send is ever lost, because the core file log already holds it. | Server | `NotificationProvider`, `secret` settings, async executor |
| ~~`mejta-logger`~~ | **Not planned for now** (rollback of chests and areas for SMPs). Not created. | | |

**Persistence (decision 5):** atomic JSON files through a new core API. No database and no new library.

**Logging rule (your requirement, 2026-10-18): everything that matters is published as a MejtaCore notification.** This includes anti-cheat flags, kicks, config changes, and in future addons admin actions (vanish, invsee, freeze), corpses and perf alerts. Then:
- **MejtaDiscord** (optional) routes each channel to its own Discord channel.
- **Without MejtaDiscord**, MejtaCore's built-in file log (`mejta-data/mejtacore/notifications.jsonl`, since 1.2.1) and each addon's own records keep everything locally.
- Kicks are always published, independent of the flag-alert settings, so staff can decide on bans.

New addons must follow this: one channel per kind of event, and no secrets or IP addresses in notifications.

## 7. Order

1. ~~**M1:** inspect, back up, set up git, rename, plan.~~ Done. Then: ~~merge into the monorepo~~; push to private GitHub.
2. **M2: close the field-test gaps in MejtaAC.** Done locally in MejtaAC 0.5.0:
   - **JUnit 6.1.3** unit tests for every check's pure logic: 48 tests.
   - `movement.jump` and `vehicle.boat_fly`, with the false-positive scenarios from section 2. Stage 5 suite under all five latency profiles: **zero legitimate-play flags**, and every cheat detected.
   - Teleports and respawns (ender pearls, `/tp`) now reset movement prediction like knockback does. Movement is also not predicted for 10 ticks after leaving a vehicle.
   - **Open:** a single MEDIUM boat flag on a slime bounce in one early run, not reproduced since. A legitimate field session with boats is needed.
3. **M3: MejtaCore 1.3.0 (API 4).**
   - Events, a bounded async executor, JSON persistence, a scheduler, metrics, and `secret` and `experimental` settings.
   - Permission bridge and notification upgrades.
   - Compatibility tests with old-API addons.
4. **M4: `mejta-perf`.** It comes early, so the remaining work can be measured and optimised as it is built.
5. **M5: MejtaAC 0.6.**
   - Per-check processors and mitigation actions.
   - Remaining vehicle checks (horse, strider) and elytra checks.
   - Remaining movement, combat, interaction, inventory and packet-abuse checks.
6. **M6:** `mejta-utils`, `mejta-corpse`, `mejta-discord`, then `mejta-qol`.

Each milestone ends with every gametest suite passing, an updated test server and a commit. Pushes go to the private GitHub repository.

## 8. Decisions (answered 2026-10-18)

1. **Hack-client session:** Fly, KillAura, Reach, Hitbox, Not Looking and FakeLag were flagged. **BoatFly and Jump Height were missed.** Watch for false positives with jump height, dropping boats and fake lag (section 2).
2. **Hosting:** one GitHub repository with the projects as subfolders. **Done locally**; the push is pending.
3. **Unit tests:** JUnit 6 approved.
4. **New projects:**
   - utils = admin commands;
   - qol = player commands (new);
   - corpse, discord and perf are separate;
   - logger is not planned.
5. **Persistence:** JSON files.

---

## Appendix: vanilla facts the anti-cheat relies on

Re-check these on **every** Minecraft update. They are the assumptions most likely to break silently.

- **Packet order:**
  - Attacks arrive before the same tick's move packet, and the rotation used for the attack is in that move packet.
  - `ServerboundClientTickEndPacket` is sent every client tick (not while paused in single-player).
  - Clients answer `ClientboundPingPacket` in order. MejtaAC's ping id is `0x4D41_0000 | (tick & 0xFFFF)`.
- **Threading:** vanilla handlers first run on the netty thread, and `PacketUtils.ensureRunningOnSameThread` reschedules them by throwing. The mixins inject **after** that call. Pongs are rescheduled so their order relative to moves is kept.
- **Ranges:**
  - Attack range is `effectiveMaxRange + hitboxMargin`.
  - Vanilla allows reach + 3 and block interaction + 1.
  - Entity and block interaction ranges are player attributes (creative changes them).
- **Air physics:** `vy = (vy − gravity) × 0.98`. A client with an unloaded chunk below falls at a constant −0.098.
- **Horizontal physics:**
  - On the ground: acceleration `speed × 0.216 / friction³`, decay `friction × 0.91`.
  - In the air: acceleration 0.02 (0.026 sprinting), decay 0.91.
  - Sprint-jump adds 0.2.
  - Acceleration uses the ground state at the end of the previous tick; decay uses the state two ticks ago.
- **Entity tracking:** entities update at their type's update interval, and the client interpolates over 3 steps.
- **Mining:**
  - Breaks below 70 % progress are rejected and then destroyed later.
  - Mining speed is ÷5 in the air and scaled by `SUBMERGED_MINING_SPEED` under water.
- **Fabric API names used in 26.1.2:**
  - `PayloadTypeRegistry.clientboundPlay/serverboundPlay`
  - `ServerPlayNetworking`
  - `CommandRegistrationCallback`
  - `ServerTickEvents.END_SERVER_TICK`
  - `ServerPlayConnectionEvents`

The latency suite (`MejtaAcStage4GameTest`) is the safety net: run it after any update before trusting a check.
