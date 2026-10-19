# Mejta

Server-side Fabric mods for Minecraft Java **26.1.2** (Fabric Loader 0.19.5+, Fabric API 0.155.3+26.1.2, Java 25). **MejtaCore** is the shared platform; every other mod is an addon that uses only MejtaCore's API and never depends on another addon, so you install only what you want. Players need no mods.

| Mod | Version | What it does |
|---|---|---|
| [MejtaCore](mejta-core/README.md) | 1.4.0 | Settings with in-game editing (also from a chest menu for vanilla clients), permissions, notifications, secrets, data store, audit log, server menus, scheduler and metrics. Required by every addon. |
| [MejtaAC](mejta-ac/README.md) | 1.1.0 | Anti-cheat with latency-compensated movement, combat, vehicle and interaction checks. Starts in `DRY_RUN`. |
| [MejtaUtils](mejta-utils/README.md) | 1.0.0 | Staff tools: `/invsee`, `/ecsee`, `/status`, `/vanish`, `/freeze`, `/staffmode`. |
| [MejtaDiscord](mejta-discord/README.md) | 1.0.0 | Anti-cheat alerts and chat to Discord webhooks; (experimental) Discord chat to Minecraft. |
| [MejtaPerf](mejta-perf/README.md) | 1.0.0 | TPS/MSPT monitor, lag alerts, a sampling profiler and reports (`/mperf`). |

Download the jars from [Releases](https://github.com/Slowing4356/mejta/releases). Put MejtaCore, Fabric API and the addons you want in the server's `mods/` folder; settings appear in `config/mejta/`. Each mod's README lists its commands, permissions and settings, and its `CHANGELOG.md` what changed.

## Building

Each folder is a standalone Gradle project with its own wrapper. Addons compile against MejtaCore from Maven Local, so publish it first:

```sh
cd mejta-core && ./gradlew publishToMavenLocal
cd ../mejta-ac && ./gradlew build   # jar in build/libs/
```

## CI and releases

- **CI** (`.github/workflows/ci.yml`) builds every project and runs the unit tests on pushes to `main` and on pull requests.
- **Client game tests** (a real client joins a dedicated server under a virtual display) take up to 40 minutes, so they run on demand: **Actions > CI > Run workflow**, then pick `all` or one project. Run them before a release.
- **Releases** (`.github/workflows/release.yml`): pushing a tag such as `v2026.10.1` builds and tests the mods again and publishes a GitHub release with their jars, `SHA256SUMS.txt` and [`RELEASE-NOTES.md`](RELEASE-NOTES.md). An existing release for the tag is updated instead.

## Repository rules

- **No secrets and no player data.** Bot tokens, webhook URLs, `.env` files, keys, `mejta-data/` and the local test server (`server/`) are ignored and never committed.
- **LF line endings** everywhere except `.bat` files (`.gitattributes`).
- **Addons talk only through MejtaCore's API.** Reusable features go into MejtaCore first.

## Planned

- **MejtaQoL:** player commands such as `/tpa` and homes.
- **MejtaCorpse:** corpses that keep a dead player's items.
