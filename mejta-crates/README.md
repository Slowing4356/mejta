# MejtaCrates

MejtaCrates (mod ID `mejtacrates`) adds **crates** opened with **keys** to a server running **MejtaCore**:
- Each crate has **weighted rewards**: items (with every enchantment, name and component), console commands (ranks, kits, other mods' rewards) and money through MejtaCore's economy, in any mix.
- **Opening animation:** the rewards spin through a menu, slow down and stop on the prize. **Preview** shows every reward with its chance.
- **Keys for donations and events:** staff, the console and donation stores (Tebex, CraftingStore and similar, which run console commands) give keys with `/crates give`. That works for offline players, and even for buyers who have **never joined**: their keys wait for their first join.
- **Virtual keys** (a count per player, opened from `/crates`) or **physical keys** (items that can be traded).
- **Crate blocks:** make any block a crate; right-click to open it with a key, left-click to see the rewards.
- **Keys and rewards can never be duplicated.** See [How duplication is prevented](#how-duplication-is-prevented).
- Every key given or taken, and (by default) every opening, is recorded in MejtaCore's audit log.

It is server-side only: menus are chests, so players need no mod (players with MejtaCore on their client can choose screens).

| | |
|---|---|
| Version | 1.1.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.9.0 or newer, below 2.0.0 (API version 7) |
| Optional | An economy addon such as MejtaEconomy, for money rewards. Without one, money rewards are simply not drawn. |

## Installing

1. Put `mejtacore-1.9.0.jar`, `mejtacrates-1.1.0.jar` and Fabric API in the server's `mods/` folder (and `mejtaeconomy-1.0.0.jar` for money rewards).
2. Start the server. The settings file `config/mejta/mejtacrates.json` is created on first start; every setting can also be changed in game in MejtaCore's settings editor (`/mejta config mejtacrates`, or `/mejtaadmin config` without client mods).
3. On the very first start an **example crate** (`example`) is created to show how crates look. Look at it with `/crates admin info example`, try it with `/crates give <you> example 3`, and delete it with `/crates admin delete example confirm`.

## Quick start: a crate you sell

```
/crates admin create vip VIP Crate              create the crate (ID "vip", shown as "VIP Crate")
/crates admin color vip light_purple            its colour
/crates admin icon vip                          its icon: the item in your hand
/crates admin add item vip 30                   reward: the item stack in your hand, weight 30
/crates admin add item vip 10                   another one (hold another item)
/crates admin add money vip 20 500              reward: 500 money, weight 20
/crates admin add command vip 2 lp user {player} parent add vip      reward: a command, weight 2
/crates admin reward vip 4 name VIP rank        name that reward (it is reward #4)
/crates admin block set vip ~ ~-1 ~             the block under you is now the VIP crate
```

A reward's chance is its weight divided by the sum of all weights: above, the VIP rank has 2 / 62 = 3.2%. `/crates admin info vip` and the preview show every chance.

In your donation store, set the package's command to:

```
crates give {username} vip 1
```

(Use your store's placeholder for the buyer's name.) The buyer is told in chat if they are online; otherwise the key waits for them.

## Using crates

- **`/crates`** opens the crate list with your keys. Left-click a crate to open it with a virtual key, right-click to see its rewards.
- **Crate blocks:** right-click to open (a physical key of that crate in your hand is used first, else a virtual key); without a key the rewards are shown. Left-click shows the rewards. Crate blocks cannot be broken; remove them with `/crates admin block remove <pos>`.
- **Physical keys** are glowing tripwire hooks named "&lt;crate&gt; Key" (or another item, see `keyitem`). Right-click one to open its crate (`keys.physical_open_anywhere`; when off, only at the crate's block). They are never placed.
- **The animation:** rewards spin through the middle row and stop under the hopper on your prize. **Skip** shows it at once. Closing the menu early still gives you the prize. The menu closes by itself 3 seconds after the prize is shown.
- **The prize:** items go to your inventory (what does not fit drops at your feet), commands run from the console, money is paid into your balance. Rare prizes are announced to everyone (`broadcast.chance_percent`).

## Commands

All of them are also available as `/mejtaadmin mejtacrates crates ...`, in case another mod uses `/crates`.

| Command | Permission | What it does |
|---|---|---|
| `/crates` | `mejtacrates.use` | The crate list with your keys |
| `/crates open <crate>` | `mejtacrates.use` | Open a crate with a virtual key (if `open.from_menu` is on) |
| `/crates preview <crate>` | `mejtacrates.use` | The crate's rewards and their chances |
| `/crates keys` | `mejtacrates.use` | Your keys |
| `/crates keys <player>` | `mejtacrates.admin` | Anyone's keys, also offline players', with unfinished openings |
| `/crates give <player> <crate> [amount] [virtual\|physical]` | `mejtacrates.give` (console: always) | Give keys; virtual unless `physical` is given. Works for offline players; a name the server has never seen gets the keys at its first join. `<player>` can also be a selector such as `@a` |
| `/crates giveall <crate> [amount] [virtual\|physical]` | `mejtacrates.give` | Give keys to everyone online (key-all events) |
| `/crates take <player> <crate> <amount>` | `mejtacrates.give` | Take virtual keys (e.g. after a chargeback) |
| `/crates admin list` | `mejtacrates.admin` | Every crate |
| `/crates admin info <crate>` | `mejtacrates.admin` | A crate's rewards, numbered, with weight, chance and contents |
| `/crates admin create <id> [name]` | `mejtacrates.admin` | A new, empty crate. IDs: lowercase letters, digits, `_` and `-` |
| `/crates admin rename <crate> <name>` | `mejtacrates.admin` | The name players see |
| `/crates admin color <crate> <colour>` | `mejtacrates.admin` | The name's colour (`gold`, `aqua`, `light_purple`...) |
| `/crates admin icon <crate>` | `mejtacrates.admin` | The icon in the crate list: the item in your hand |
| `/crates admin keyitem <crate>` | `mejtacrates.admin` | What new physical keys are made of: the item in your hand (existing keys keep working) |
| `/crates admin add item <crate> <weight>` | `mejtacrates.admin` | Reward: the stack in your hand, exactly as it is |
| `/crates admin add command <crate> <weight> <command>` | `mejtacrates.admin` | Reward: a console command. `{player}`, `{uuid}` and `{crate}` are filled in. The item in your hand (if any) becomes its icon |
| `/crates admin add money <crate> <weight> <amount>` | `mejtacrates.admin` | Reward: money (`500`, `12.50`, `2k`) |
| `/crates admin reward <crate> <n> remove` | `mejtacrates.admin` | Remove reward #n (also: shift-right-click it in the preview) |
| `/crates admin reward <crate> <n> weight <weight>` | `mejtacrates.admin` | Change its weight |
| `/crates admin reward <crate> <n> name <name>` | `mejtacrates.admin` | Its name in the preview and messages (`-` removes it) |
| `/crates admin reward <crate> <n> display` | `mejtacrates.admin` | Its icon: the item in your hand |
| `/crates admin reward <crate> <n> additem` | `mejtacrates.admin` | Add the stack in your hand to it (one reward can give several things) |
| `/crates admin reward <crate> <n> addcommand <command>` | `mejtacrates.admin` | Add a command to it |
| `/crates admin reward <crate> <n> broadcast on\|off\|auto` | `mejtacrates.admin` | Always, never, or by `broadcast.chance_percent` announce wins of it |
| `/crates admin block set <crate> <pos>` | `mejtacrates.admin` | Make the block at `<pos>` a crate (aim at it and press Tab for its coordinates) |
| `/crates admin block remove <pos>` | `mejtacrates.admin` | The block is no longer a crate |
| `/crates admin block list` | `mejtacrates.admin` | Every crate block |
| `/crates admin delete <crate> confirm` | `mejtacrates.admin` | Delete a crate and its blocks. Players' keys stay and work again if a crate with the same ID is created |
| `/crates admin reload` | `mejtacrates.admin` | Read the crate files again after editing them by hand |
| `/mejtaadmin mejtacrates status` | `mejtacrates.admin` | Number of crates, blocks and player files, and which economy is used |

## Permissions

Change their levels in `config/mejta/mejtacore.permissions.json`.

| Permission | Default | What it allows |
|---|---|---|
| `mejtacrates.use` | everyone | Open crates with your keys, preview them, `/crates` |
| `mejtacrates.give` | admins | Give and take keys (`give`, `giveall`, `take`). The console always may |
| `mejtacrates.admin` | admins | Create and edit crates and crate blocks, see anyone's keys; the preview shows every reward with its number |
| `mejtacrates.alerts` | moderators | See in chat when keys are given or taken, when a crate is deleted, when a copied key is caught and when a reward could not be handed out |

## Settings

In `config/mejta/mejtacrates.json`, or in game with MejtaCore's settings editor.

| Setting | Default | What it does |
|---|---|---|
| `open.animation` | `true` | Spin the rewards before showing the prize. Off: the prize is given at once |
| `open.animation_ticks` | `80` | How long the spin lasts (20 to 200 ticks; 20 ticks = 1 second) |
| `open.sounds` | `true` | Sounds while spinning and when the prize is shown |
| `open.from_menu` | `true` | Players may open crates with virtual keys from `/crates`. Off: only at crate blocks |
| `keys.physical_open_anywhere` | `true` | Right-clicking a physical key opens its crate anywhere. Off: only at the crate's block |
| `keys.notify` | `true` | Tell players when they receive keys, and at join how many they have |
| `broadcast.chance_percent` | `5.0` | Announce wins of rewards with this chance or less. `0` = only rewards set to `broadcast on` |
| `audit.openings` | `true` | Record every opening and its prize in the audit log |

## Files

Everything is in MejtaCore's data store, `mejta-data/mejtacrates/store/`, written atomically with three backups of each file:

- `crates/<id>.json`: one crate. You may edit these by hand and then run `/crates admin reload`. Rewards that cannot be read are skipped and named in the server log.
- `players/<uuid>.json`: a player's virtual keys, physical keys waiting for their next join, and openings whose prize was not handed out yet.
- `names/n-<name>.json`: keys given to a name that has never joined.
- `serials/<serial>.json`: one batch of physical keys: crate, how many were issued and used, to whom and by whom.
- `blocks.json`: the crate blocks.

A crate file looks like this (items use the game's own item format, as in `/give` components):

```json
{
  "name": "VIP Crate",
  "color": "LIGHT_PURPLE",
  "icon": {"id": "minecraft:ender_chest", "count": 1},
  "rewards": [
    {"weight": 30, "items": [{"id": "minecraft:diamond", "count": 8}]},
    {"weight": 20, "money": 50000},
    {"weight": 2, "name": "VIP rank", "display": {"id": "minecraft:nether_star", "count": 1},
     "commands": ["lp user {player} parent add vip"], "broadcast": true}
  ]
}
```

`money` is in the economy's smallest unit: with 2 decimals, `50000` is 500.00.

## How duplication is prevented

- **Virtual keys** are a count in the player's file. Every change (give, take, open) is on the disk before anything else happens.
- **Opening** takes the key and records the drawn prize as a *pending opening* in one write, before the animation starts. The prize is handed out only after that opening has been removed from the file again, so it is handed out **at most once**, however the opening ends: the animation finishing, **Skip**, closing the menu, two clicks at once.
- If the player **disconnects** during the animation, or the **server stops or crashes**, the opening stays pending and its prize is handed out at the player's next join.
- **Physical keys** carry the serial of their batch, and the batch's file counts how many of its keys were used. A batch opens at most as many crates as keys were issued in it. A key copied by a glitch or another mod is therefore worthless: once the real keys are used, the copy is taken away when used, and staff with `mejtacrates.alerts` are told (`keys.copied` in the audit log).
- **Keys given by name** to someone who never joined are moved to the player's file at their first join, and the player's file remembers that, so they are never added twice.
- Keys cannot be added to a crate as item rewards.

## Audit log

MejtaCore's audit log (`/mejtaadmin audit`) records `keys.give`, `keys.take`, `keys.claim` (keys given by name, moved at the first join), `keys.copied`, `crate.open` (with the prize, if `audit.openings` is on, and always when something could not be handed out), `crate.edit` and `crate.delete`.
