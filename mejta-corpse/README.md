# MejtaCorpse

MejtaCorpse (mod ID `mejtacorpse`) keeps a dead player's items in a **corpse** instead of scattering them on the ground, in the spirit of the [Corpse](https://modrinth.com/mod/corpse) mod, for servers running **MejtaCore**:
- The corpse lies where the player died, wearing their skin and the armour it holds, with "<player>'s corpse" above it.
- Right-click it to open a chest menu with the items, laid out like the inventory screen.
- **Take all** gives the owner every item back in its old slot, armour included.
- **Items can never be duplicated.** Not by clicking, not by two players at once, not by a crash or a full disk. See [How duplication is prevented](#how-duplication-is-prevented).
- **Death backups:** the items of each player's last 10 deaths are kept, so staff can give them back after the corpse is gone (looted, expired or destroyed). See [Death backups](#death-backups).

It is server-side only: the corpse is a vanilla mannequin and the menu is a vanilla chest, so players need no mod.

| | |
|---|---|
| Version | 1.2.0 |
| Minecraft | 26.1.2, Fabric Loader 0.19.5 or newer, Fabric API 0.155.3+26.1.2, Java 25 |
| Requires | MejtaCore 1.4.0 (any version from 1.3.0 up to, not including, 2.0.0 is accepted) |
| Other libraries | None |

## Installing

1. Put `mejtacore-1.7.0.jar`, `mejtacorpse-1.2.0.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. The settings file `config/mejta/mejtacorpse.json` is created on first start.

With the `keepInventory` game rule on, players keep their items and no corpse appears. Items with Curse of Vanishing disappear as in vanilla. Experience drops as in vanilla.

## Using a corpse

- **Open:** right-click the corpse. Only its owner can open it, unless `access.public_after_minutes` makes it public, or the player has `mejtacorpse.open_any`.
- **Take items:**
  - click to pick up an item, or shift-click to move it to your inventory;
  - a number key moves the item into that hotbar slot, if the slot is empty;
  - **Take all** moves everything at once.
  - Nothing can be put into a corpse.
- **Menu layout:** rows 1 to 3 are the main inventory and row 4 the hotbar. Row 5 holds the helmet, chestplate, leggings, boots and offhand item. Row 6 has the info, **Take all** and **Close** buttons.
- **Two players at once** see the same items live, so an item can only be taken once.
- **Armour you can see:** the corpse wears the armour it holds. It is only a display copy: the items stay in the corpse, and a piece taken from the menu also leaves the body.
- **When the last item is taken** and the menu is closed, the corpse disappears.
- **Corpses cannot be hurt or pushed.** If a command kills or removes one, it is put back at once with its items.
- **Dying in the void:** the corpse stays at the bottom of the world, and its owner can open it from anywhere with `/corpse open <id>`.

When you die, chat tells you where your corpse is. `/corpse` lists your corpses.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/corpse` | `mejtacorpse.use` | Your corpses: ID, place, number of stacks, age, with an **[Open]** button where you may open it from afar |
| `/corpse open <id>` | the owner, if `access.owner_remote_open` is on or they died in the void; `mejtacorpse.admin` for any corpse | Open a corpse from anywhere |
| `/corpse list <player>` | `mejtacorpse.admin` | Any player's corpses, also offline players' |
| `/corpse tp <id>` | `mejtacorpse.admin` | Teleport to a corpse (audited) |
| `/corpse backups <player>` | `mejtacorpse.restore` | A player's death backups, newest first, with what happened to each corpse and a **[Restore]** button, also for offline players |
| `/corpse restore <player> <id>` | `mejtacorpse.restore` | Shows what a backup would give back and asks to confirm; `... confirm` gives the items to the player (online) |
| `/mejtaadmin mejtacorpse status` | `mejtacorpse.admin` | Number of corpses, active and open ones, and the data scope |

`/corpse` is also available as `/mejtaadmin mejtacorpse corpse`, in case another mod uses the name.

## Permissions

Change their levels in `config/mejta/mejtacore.permissions.json`.

| Node | Default level | Allows |
|---|---|---|
| `mejtacorpse.use` | 0 (everyone) | Opening your own corpses, `/corpse` |
| `mejtacorpse.open_any` | 3 (admins) | Opening anyone's corpse (audited); also receives the "corpse locked" alert |
| `mejtacorpse.admin` | 3 (admins) | `/corpse list`, opening any corpse from afar, `/corpse tp`, `status` |
| `mejtacorpse.restore` | 3 (admins) | `/corpse backups` and `/corpse restore` for other players' deaths (audited) |
| `mejtacorpse.restore.self` | 4 (owners) | Also restoring your own deaths (with `mejtacorpse.restore`) |

## Settings

Stored in `config/mejta/mejtacorpse.json`. Change them in the file, in MejtaCore's settings menu (`/mejtaadmin config menu mejtacorpse`), or with `/mejtaadmin config set mejtacorpse <key> <value>`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Create corpses. Off: items drop as in vanilla; existing corpses stay and can still be opened. |
| `access.public_after_minutes` | `-1` (-1 to 10080) | Minutes after which anyone may loot a corpse. `-1`: only the owner and staff; `0`: anyone at once. |
| `access.owner_remote_open` | `false` | Owners may open their corpses from anywhere with `/corpse open`. Void corpses always may. |
| `access.max_distance` | `6` (2–16) | Blocks a player may move away before an open corpse menu closes |
| `expire.minutes` | `0` (0–525600) | Minutes until a corpse with items expires. `0`: never. |
| `expire.action` | `DROP_ITEMS` | `DROP_ITEMS` (the items fall where the corpse lay) or `DELETE_ITEMS` |
| `display.player_skin` | `true` | The corpse wears the player's skin; off: the default skin |
| `display.show_name` | `true` | Show "<player>'s corpse" above it |
| `display.show_armor` | `true` | The corpse wears a display copy of the armour it holds |
| `notify.death_message` | `true` | Tell players where their corpse is when they die |
| `audit.looting` | `true` | Record in the audit log when someone other than the owner takes items |
| `backup.per_player` | `10` (0–100) | Deaths per player whose items are backed up; the oldest go first. `0`: no new backups (existing ones are kept). |
| `backup.keep_days` | `0` (0–3650) | Days after which a backup is deleted, checked when its owner dies or joins. `0`: only newer deaths replace it. |
| `backup.notify_owner` | `true` | Tell players when staff restore one of their deaths |

An expiring corpse only expires while its chunk is loaded, so expiry never loads chunks.

## How duplication is prevented

The corpse's stored record (`mejta-data/mejtacorpse/store/corpses/<id>.json`) is the only copy of its items. The entities in the world hold none of them; the armour the body wears is a display copy:
- a mannequin never drops its equipment, so killing the body drops nothing;
- every click on the body opens the menu instead of reaching the mannequin;
- dispensers cannot put armour on it;
- the copy is rewritten from the record whenever items are taken, and when the body is put back.

So killing, copying or reloading the entities changes nothing. Every step that moves items writes to disk with a forced flush before the game can save the player:

1. **Death.** The game drops the inventory in one place. MejtaCorpse replaces that step:
   1. The corpse is written as **pending**, holding the items.
   2. The items leave the inventory, and **the player is saved** (only that player, with the game's own save).
   3. The corpse is written as **active**. Only now can it be opened.

   If the server stops in between, the corpse stays pending. At the owner's next join, their inventory shows which side was saved. If they still hold the items, the death was never saved and the corpse is deleted. If not, the corpse becomes active. If the corpse or the player cannot be saved, nothing is moved, and the items drop as in vanilla.
2. **Taking items.** Every way the game's menu code takes items out ends in one method. That method writes the corpse without them to disk, and only then hands them out. If the write fails, nothing is handed out. Anything else that changes during a click (an item that did not fit going back) is written before the click ends. If such a write fails, the corpse is locked until a restart, and staff get a `mejtacorpse.error` alert.
3. **Nothing goes in.** Corpse slots refuse every item, and number-key swaps only move items out into an empty slot. The buttons cannot be picked up.
4. **One copy in memory.** Everyone viewing a corpse shares one set of items, so a stack cannot be taken twice.
5. **Expiry** writes the emptied corpse first, then drops the items.

A crash can therefore lose the items of an unfinished click (as a crash in vanilla can), but never leave an item both in a corpse and with a player.

## Death backups

When a corpse is created, a copy of its items is also written to `mejta-data/mejtacorpse/store/backups/<player uuid>/`. Each player keeps the backups of their last 10 deaths (`backup.per_player`); a backup is a few kilobytes. The copy is only a record: the items live in the corpse, and taking them from the corpse works as before.

The backup also records what then happens to the corpse: who took how many items (the owner or someone else), and whether it expired. Deaths without a corpse (corpses off, `keepInventory`, an empty inventory) have no backup.

**Restoring.** A player says their corpse was looted, despawned or lost to a bug:
1. `/corpse backups <player>` lists their deaths, newest first, for example `#3fa2c1d0  2 h ago  120, 64, -88 (overworld)  31 stack(s)  corpse gone; Alex took 412 [Restore]`.
2. **[Restore]** (or `/corpse restore <player> <id>`) shows the items' count, where and when they died, what happened to the corpse, and a warning if the owner already took items back themselves. Nothing is given yet.
3. **[Confirm restore]** (`/corpse restore <player> <id> confirm`) gives the items back: each into its old slot if that is empty, else anywhere in the inventory; what does not fit drops at the player's feet. The player is then saved.

Restoring creates items, so it is guarded against abuse and duplication:
- only staff with `mejtacorpse.restore`; restoring your own death also needs `mejtacorpse.restore.self` (owners only by default);
- only once the corpse is gone: while it exists, its items are still in it (`/corpse open <id>`);
- each backup only once: it is marked restored on disk **before** any item is handed out, and if that write fails nothing is given;
- the owner must be online and alive;
- every restore is in the audit log (`corpse.restore`, with the staff member, the player, the number of items and how many the owner had taken back), in the server log, and announced to everyone with `mejtacorpse.restore`.

Backups are kept per world on singleplayer and LAN worlds, like corpses.

## Data and audit

- **Data:** `mejta-data/mejtacorpse/store/corpses/<id>.json` holds each corpse's items as written by the game's own item format (enchantments, names, contents), its place, owner, and the owner's skin texture. On a singleplayer or LAN world it is kept per world (under `store/worlds/<id>/`).
- **Audit:**
  - looting by someone other than the owner (`corpse.loot`, with the number of items);
  - staff opening (`corpse.open`) and teleporting (`corpse.teleport`);
  - expiry (`corpse.expire`);
  - restoring a death backup (`corpse.restore`).

  The audit log is at `/mejtaadmin audit recent`. Ordinary deaths are not recorded.

## Building and testing

```bash
cd mejta-core && ./gradlew publishToMavenLocal   # MejtaCore 1.7.0 first
cd ../mejta-corpse && ./gradlew build            # compile and run unit tests
./gradlew runGameTest                            # headless server game test
xvfb-run ./gradlew runClientGameTest             # real client on a dedicated server (needs a display)
```

- **Unit tests** cover:
  - the record format, the menu layout (every slot maps both ways exactly once) and the access and expiry rules;
  - that a failed write changes neither memory nor disk;
  - every default setting;
  - death backups: their format and history, keeping only the newest per player and the age limit.
- **The server game test** starts a headless server with MejtaCore and MejtaCorpse, so the death hook must apply. With Fabric's fake players and the real menu code, it checks:
  - a corpse is created, a lying mannequin with an interaction box, and the inventory is emptied;
  - the body wears a copy of the armour and nothing else, killing it drops nothing, and a dispenser cannot equip it;
  - another player cannot open it;
  - a removed body comes back with the items untouched, wearing the armour again;
  - shift-click, a repeated shift-click, double-click, placing items, a number-key swap, button clicks and Take all (armour back in place, and off the body) each move every item exactly once, on disk and in the inventory;
  - an empty corpse is removed;
  - while the disk refuses writes, no click, drop or Take all hands anything out;
  - an unfinished death is resolved correctly at join, both ways;
  - a real death of a player who cannot be saved falls back to vanilla drops, each item dropped once;
  - the death is backed up; it cannot be restored while the corpse exists, asks to confirm, puts the items back in their slots, and is restored only once.

- **The client game test** runs a real client on a dedicated server, with a real player and no fake players:
  - the player dies; chat names the corpse's place, no items drop and the inventory is empty after respawning;
  - the client sees the corpse wearing the player's armour;
  - hitting the corpse does nothing, and right-clicking it opens the menu;
  - a real shift-click takes a stack once, and a picked-up stack cannot be put back;
  - Take all puts the armour back on and the shield in the offhand, gives every item exactly once and closes the menu;
  - the empty corpse is removed.

  It saves screenshots of the corpse from three sides, the menu and its tooltips in `build/run/clientGameTest/screenshots/`. The test player is offline, so the corpse shows a default skin there; on an online-mode server it shows the player's own skin.

## Limitations

- The corpse is a vanilla mannequin. Its lying direction and look are vanilla's, and it does not turn into a skeleton over time.
- Items from mods that add their own inventory slots outside the vanilla 41 are dropped by those mods as usual.
- Experience is not stored in the corpse.
