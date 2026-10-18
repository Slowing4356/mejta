# Mejta

Mejta is a family of Fabric mods for Minecraft Java **26.1.2** (Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25). **MejtaCore** is the shared platform. Every other mod is an addon that uses only MejtaCore's public API and never depends on another addon.

| Folder | Mod | Status | Runs on |
|---|---|---|---|
| [`mejta-core/`](mejta-core/README.md) | **MejtaCore**: addon registry, configuration (with in-game server editing), permissions, notifications, record logs, `/mejta` menu, `/mejtaadmin` | 1.2.1 (API 3) | Server and client |
| [`mejta-ac/`](mejta-ac/README.md) | **MejtaAC**: server-authoritative anti-cheat with lag-compensated checks | 0.5.0 | Server only |
| `mejta-perf/` | **MejtaPerf**: Spark-style profiler for the ecosystem (optional) | planned | Server (+ client) |
| `mejta-utils/` | **MejtaUtils**: admin tools (`/invsee`, `/vanish`, …) | planned | Server |
| `mejta-qol/` | **MejtaQoL**: player commands (`/tpa`, `/homes`, …) | planned | Server |
| `mejta-corpse/` | **MejtaCorpse**: corpses that hold a dead player's items | planned | Server |
| `mejta-discord/` | **MejtaDiscord**: sends MejtaCore notifications to Discord | planned | Server |

Each folder is a standalone Gradle project with its own wrapper, README and version. Build one with `cd <folder> && ./gradlew build`. An addon compiles against MejtaCore from Maven Local, so publish that first with `cd mejta-core && ./gradlew publishToMavenLocal`.

[`MIGRATION-PLAN.md`](MIGRATION-PLAN.md) holds the roadmap, the check inventory and the threat model.

## Repository rules

- **No secrets and no player data.** Tokens, webhook URLs, `.env` files, keys, `mejta-data/` records and the local test server (`server/`) are ignored.
- **LF line endings** everywhere except `.bat` files; `.gitattributes` enforces this.
- History from before the monorepo is kept: `git log --follow mejta-ac/<file>` reaches back to the first commit.
