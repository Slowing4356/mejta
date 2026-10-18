# Mejta

Mejta is a family of Fabric mods for Minecraft Java **26.1.2** (Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25). **MejtaCore** is the shared platform. Every other mod is an addon that uses only MejtaCore's public API and never depends on another addon.

| Folder | Mod | Status | Runs on |
|---|---|---|---|
| [`mejta-core/`](mejta-core/README.md) | **MejtaCore**: addon registry, configuration (with in-game server editing), permissions, notifications, record logs, `/mejta` menu, `/mejtaadmin`, secrets, data store, audit log, presence, server menus, scheduler, metrics | 1.3.0 (API 4) | Server and client |
| [`mejta-ac/`](mejta-ac/README.md) | **MejtaAC**: server-authoritative anti-cheat with lag-compensated checks | 1.0.0 | Server only |
| [`mejta-perf/`](mejta-perf/README.md) | **MejtaPerf**: TPS/MSPT monitor, lag alerts, a profiler that charges server time to mods, reports (`/mperf`) | 1.0.0 | Server |
| [`mejta-utils/`](mejta-utils/README.md) | **MejtaUtils**: admin tools (`/invsee`, `/ecsee`, `/status`, `/vanish`, `/freeze`, `/staffmode`) | 1.0.0 | Server |
| `mejta-qol/` | **MejtaQoL**: player commands (`/tpa`, `/homes`, …) | planned | Server |
| `mejta-corpse/` | **MejtaCorpse**: corpses that hold a dead player's items | planned | Server |
| [`mejta-discord/`](mejta-discord/README.md) | **MejtaDiscord**: anti-cheat alerts to Discord, chat between Discord and Minecraft | 1.0.0 | Server |

Each folder is a standalone Gradle project with its own wrapper, README, changelog and version. Build one with `cd <folder> && ./gradlew build`. An addon compiles against MejtaCore from Maven Local, so publish that first with `cd mejta-core && ./gradlew publishToMavenLocal`.

**CI** (`.github/workflows/ci.yml`) builds every project and runs the unit tests on every push to `main` and every pull request; that takes a few minutes, and the built jars are attached to the run. The client game tests (a real client against a dedicated server, under a virtual display) take up to half an hour, so they run only on demand: **Actions > CI > Run workflow**, then choose `all` or one project. Run them before tagging a release. **Releases** (`.github/workflows/release.yml`): pushing a tag such as `v2026.10.0` builds and tests everything again and publishes a GitHub release with all jars, checksums and `RELEASE-NOTES.md`.

[`MIGRATION-PLAN.md`](MIGRATION-PLAN.md) holds the roadmap, the check inventory and the threat model. [`INTEGRATION-DESIGN.md`](INTEGRATION-DESIGN.md) holds the design of MejtaUtils, MejtaDiscord and the MejtaCore 1.3.0 API they need, and what was built (its section 8).

## Repository rules

- **No secrets and no player data.** Tokens, webhook URLs, `.env` files, keys, `mejta-data/` records and the local test server (`server/`) are ignored.
- **LF line endings** everywhere except `.bat` files; `.gitattributes` enforces this.
- History from before the monorepo is kept: `git log --follow mejta-ac/<file>` reaches back to the first commit.
