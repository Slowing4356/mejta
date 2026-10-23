# MejtaSMP

MejtaSMP (mod ID `mejtasmp`) adds the things a survival multiplayer server usually wants, for servers running **MejtaCore**. Every feature can be switched off or tuned in its settings:
- **AFK protection:** AFK players cannot be hurt by other players.
- **Scoreboards:** a sidebar with your stats or a top list, a `/leaderboard` menu, and an optional score in the player list.
- **Less lag from drops:** item and XP clumping, and dropped items merging into piles bigger than a stack, labelled with their size.
- **World rules:** infinite villager trades, one-player sleep, Nether and End toggles, and the locator bar off.
- **Account limit:** a cap on accounts per internet address.

It is server-side only: menus are plain chests and the sidebar is a vanilla scoreboard, so players need no mod.

| | |
|---|---|
| Version | 1.1.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.9.0 (any version from 1.9.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None |

## Installing

1. Put `mejtacore-1.9.0.jar`, `mejtasmp-1.1.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. The settings file `config/mejta/mejtasmp.json` is created on first start.
3. Change settings in MejtaCore's settings menu (`/mejtaadmin config menu mejtasmp`). The settings are grouped there: AFK protection, Scoreboard, XP, Drops, Villagers, Sleep, Dimensions and Antialt.

## Features

### AFK protection

While a player is AFK, other players cannot hurt them, and the attacker is told so. AFK status comes from MejtaCore: a player is AFK after `afk.auto_minutes` idle (5 by default, in `mejtacore.json`), or with MejtaQoL's `/afk`.
- **No escaping a fight:** a player in combat (MejtaCore's `combat.tag_seconds`, 10 seconds after any PvP hit) is never protected, and MejtaQoL refuses `/afk` in combat.
- **Mobs:** optionally, mobs cannot hurt AFK players either.
- **Still harmful:** falling, lava, hunger and the like.

### Scoreboards

- **Sidebar:** each player picks what their sidebar shows with `/sb`, a menu. The choice is kept.
  - **Your stats:** kills, deaths, K/D, kill streak (and best streak), mob kills and play time.
  - **Top kills** or **Top play time:** the 10 best players.
  - **Off.**

  The sidebar is sent only to that player, so everyone can see something different. The server's own scoreboard is untouched; a sidebar the server sets with `/scoreboard` comes back when a player turns MejtaSMP's off.
- **`/leaderboard`** (or `/lb`): a menu with the top 21 players by kills, deaths, play time, mob kills or best kill streak, with their heads, and your own rank.
  - **Offline players:** they stay on it.
  - **Skins:** heads show the skin of players who are online.
- **Player list:** `scoreboard.tab_list` shows kills, deaths or health next to every name in the Tab list.

Kills, deaths, play time and mob kills come from the game's own statistics; kill streaks are counted by MejtaSMP. Numbers are refreshed every 2 seconds.

### Items and experience

- **XP clumps:** experience orbs near each other merge into one orb, whatever their values, like the Clumps mod. One orb holds up to 32,767 experience, the most the game can save.
- **Instant XP pickup:** a player takes a whole orb at once. Mending works as usual.
- **Wider item merging:** equal dropped items merge from up to `drops.merge_radius` blocks away (vanilla: half a block), like Get It Together, Drops! As in vanilla, items that never stack (tools, armour, potions) do not merge, so farms full of them cost nothing extra.
- **Piles (experimental):** dropped items merge into piles bigger than a stack. 1,000 cobblestone from a farm is one item entity instead of 16, with "Cobblestone ×1,000" above it. A player, hopper or mob takes a stack at a time, as from any item. See [How piles never duplicate items](#how-piles-never-duplicate-items).

### World

- **Infinite trading:** villagers' and wandering traders' trades never run out.
- **Sleep:** `sleep.percentage` sets the share of players who must sleep to skip the night. `0` (the default) means one player is enough.
- **Dimensions:** `dimensions.nether` and `dimensions.end` close portals into that dimension.
  - Players are told why. Staff with `mejtasmp.dimensions.bypass` may still go, if `op_bypass.enabled` is on.
  - No portal is built on the other side.
  - The way back out of a dimension stays open, and commands still work.
- **Locator bar:** hidden by default (`dimensions.locator_bar`).

### Account limit

- **Online at once:** at most `antialt.max_online` accounts may play from one internet address (2 by default).
- **Different accounts:** optionally, at most `antialt.max_accounts` different accounts may ever join from one address.
- **Refusals:** a refused player sees `antialt.kick_message`, and staff with `mejtasmp.antialt.alerts` are told who was refused and which accounts were already online. Alerts and the log show names only.
- **Privacy:** addresses are never stored, shown or logged. Each one becomes a salted SHA-256 hash, and the salt is random for each server. Only the hash and the accounts seen with it are kept, and only while `antialt.max_accounts` is set. Connections from the server's own machine are never limited.

Players behind the same home connection share an address, so raise `antialt.max_online` if families play together. Behind a proxy (Velocity, BungeeCord), the limit only works when the proxy forwards real player addresses.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/sb` | `mejtasmp.sidebar` | Menu to pick your sidebar |
| `/sb off\|stats\|kills\|playtime` | `mejtasmp.sidebar` | Pick it directly |
| `/leaderboard`, `/lb` | `mejtasmp.leaderboard` | The leaderboard menu |
| `/mejtaadmin mejtasmp status` | `mejtasmp.admin` | Players with stats, accounts counted by the limit, and the data scope |

## Permissions

Change their levels in `config/mejta/mejtacore.permissions.json`.

| Node | Default level | Allows |
|---|---|---|
| `mejtasmp.sidebar` | 0 (everyone) | `/sb` and seeing the sidebar |
| `mejtasmp.leaderboard` | 0 (everyone) | `/leaderboard` |
| `mejtasmp.dimensions.bypass` | 3 (admins) | Only while `op_bypass.enabled` is on: using portals into a closed dimension |
| `mejtasmp.antialt.bypass` | 1 (moderators) | Only while `op_bypass.enabled` is on: never refused by the account limit, and not counted in it |
| `mejtasmp.antialt.alerts` | 1 (moderators) | Told when the account limit refuses someone |
| `mejtasmp.admin` | 3 (admins) | `status` |

## Settings

Stored in `config/mejta/mejtasmp.json`. Change them in the file, in MejtaCore's settings menu, or with `/mejtaadmin config set mejtasmp <key> <value>`. Changes apply at once.

| Key | Default | Meaning |
|---|---|---|
| `op_bypass.enabled` | `false` | Let `mejtasmp.dimensions.bypass` and `mejtasmp.antialt.bypass` work. Off: operators follow the dimension toggles and the account limit like everyone else. |
| `afk_protection.enabled` | `true` | AFK players cannot be hurt by other players, unless in combat |
| `afk_protection.from_mobs` | `false` | Also protect AFK players from mobs |
| `afk_protection.notify_attacker` | `true` | Tell a player who hits an AFK player that they are protected |
| `scoreboard.sidebar` | `true` | The sidebar and `/sb` |
| `scoreboard.default_view` | `MY_STATS` | What a player who has not picked one sees: `OFF`, `MY_STATS`, `TOP_KILLS`, `TOP_PLAYTIME` |
| `scoreboard.title` | `&6&lSMP` | Sidebar title; `&` colour codes allowed |
| `scoreboard.tab_list` | `NONE` | Score in the player list: `NONE`, `KILLS`, `DEATHS`, `HEALTH` |
| `scoreboard.leaderboard` | `true` | `/leaderboard` |
| `xp.clump` | `true` | Orbs near each other merge into one |
| `xp.clump_radius` | `2.0` (0.5–8) | How close orbs must be to merge, in blocks |
| `xp.instant_pickup` | `true` | Take a whole orb at once |
| `drops.merge_radius` | `1.5` (0.5–8) | How close equal dropped items must be to merge, in blocks (vanilla 0.5) |
| `drops.stack_beyond_max` | `true` | **Experimental.** Piles bigger than a stack |
| `drops.max_pile` | `50000` (64–1000000) | Most items in one pile |
| `drops.show_count` | `STACKED` | Label above piles: `NEVER`, `STACKED` (bigger than a stack) or `ALWAYS` |
| `villagers.infinite_trading` | `true` | Trades never run out |
| `sleep.percentage` | `0` (-1–100) | Percent of players who must sleep. `0` = one player; `-1` = leave the game rule alone |
| `dimensions.nether` | `true` | Portals may lead into the Nether |
| `dimensions.end` | `true` | Portals may lead into the End |
| `dimensions.locator_bar` | `HIDDEN` | `SHOWN`, `HIDDEN`, or `VANILLA` (leave the game rule alone) |
| `antialt.enabled` | `true` | The account limit |
| `antialt.max_online` | `2` (1–20) | Accounts online at once from one address |
| `antialt.max_accounts` | `0` (0–50) | Different accounts that may ever join from one address; `0` = no limit |
| `antialt.kick_message` | `Too many accounts from your connection.` | Shown to a refused player |

`sleep.percentage` and `dimensions.locator_bar` are game rules (`players_sleeping_percentage`, `locator_bar`): MejtaSMP sets them when the server starts and when the setting changes. Set the setting to `-1` or `VANILLA` to manage the rule with `/gamerule` instead.

## How piles never duplicate items

A pile is one ordinary item entity: its visible stack (at most a full stack) and a count of further items, saved with the entity.
- **Taking items:** everything in the game that takes items from an item entity (players, hoppers, mobs, allays) sees only the visible stack and takes from it as usual. When it then removes the entity, the rest of the pile continues as a new item entity at the same place.
- **Moving the count:** the count always moves, it is never copied. The old entity's count is set to 0 before the new one gets it.
- **Merging:** the smaller pile's whole count goes into the larger, and the smaller is cleared before it is removed.
- **Destroying:** an item entity destroyed with its items takes the whole pile, as with a stack. That is lava, fire, cactus, explosions, the void, despawning after 5 minutes, or `/kill`.
- **Saving and travelling:** the count is saved with the entity and kept when it goes through a portal.

If MejtaSMP is removed, piles on the ground keep only their visible stack.

## Data

`mejta-data/mejtasmp/store/` holds:
- `stats/<uuid>`: each player's numbers, for the leaderboards;
- `sidebar/<uuid>`: each player's sidebar choice;
- `antialt/salt` and `antialt/addresses/<hash>`: the account limit.

On a singleplayer or LAN world it is kept per world.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.9.0 first
cd ../mejta-smp && ./gradlew build               # compile and run unit tests
./gradlew runGameTest                            # headless server game test
xvfb-run ./gradlew runClientGameTest             # real client on a dedicated server (needs a display)
```

- **Unit tests** cover:
  - pile arithmetic (every split keeps every item, the size limit without overflow);
  - the account limit's rules;
  - stats (streaks, K/D, saving and loading, ranking) and the sidebar's lines;
  - every default setting.
- **The server game test** starts a headless server with MejtaCore and MejtaSMP, so every mixin must apply. It checks:
  - four drops merge into one pile of 202;
  - a player picks it up exactly once, a stack at a time, and with a full inventory nothing is lost or made;
  - a hopper empties a pile of 200 exactly;
  - a saved and loaded pile keeps its count;
  - lava and despawning take the whole pile, and no pile passes `drops.max_pile`;
  - orbs of different values clump and are taken at once;
  - trades never run out;
  - a closed Nether has no portal destination, and the game rules are set;
  - an AFK player cannot be hurt by a player, except in combat;
  - kill streaks and the player list score work.
- **The client game test** runs a real client on a dedicated server:
  - the sidebar arrives with the player's stats;
  - five drops become a pile labelled "×300" that the client sees, and walking over it picks up exactly 300;
  - eight orbs clump and all their experience is taken;
  - `/sb` and `/leaderboard` open.

  Screenshots are saved in `build/run/clientGameTest/screenshots/`.

## Limitations

- Piles are experimental. Mods that take items from item entities in unusual ways might take only the visible stack; the rest then continues as a new item entity.
- The leaderboard lists players who joined while MejtaSMP was installed.
- Closing a dimension affects portals only, not commands or other mods' teleports.
