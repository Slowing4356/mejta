# MejtaQoL changelog

## 1.2.0

Requires MejtaCore 1.9.0 or newer, below 2.0.0 (API version 7).

- **Homes and warps menus have pages:** every home and warp is in the menu now (it used to stop at 45), with MejtaCore's shared bar along the bottom (previous and next page, an info icon, Close) and gray panes around it. The chest is just big enough for the list. Deleting a home brings you back to the page you were on.
- Clearer icons: the location, then the clicks in colour (yellow to teleport, red to delete).

## 1.1.1

- **Operators follow the teleport rules:** the wait, cooldowns and combat lock were skipped for every operator, because operators have `mejtaqol.teleport.bypass`. Bypass permissions (`mejtaqol.teleport.bypass` and `mejtaqol.afk.kick_exempt`) now only apply while the new setting `op_bypass.enabled` is on. It is off by default, so operators follow the rules like everyone else.

## 1.1.0

Requires MejtaCore 1.5.0 or newer, below 2.0.0 (API version 5).

- **AFK and combat come from MejtaCore:** who is AFK and who is in combat is now tracked by MejtaCore, so MejtaQoL and other addons (such as MejtaSMP's AFK protection) always agree.
  - `/afk`, the broadcast, the `[AFK]` tag and the idle kick stay in MejtaQoL.
  - `/afk` is now refused while in combat.
- **Moved settings:** `teleport.combat_seconds` and `afk.auto_minutes` were removed from `mejtaqol.json` and are removed from existing files automatically. Their replacements are `combat.tag_seconds` and `afk.auto_minutes` in `mejtacore.json`, with the same defaults (10 seconds, 5 minutes). If you had changed them, set them again there.
- **`/rtp` is lighter:** the cooldown now starts when a search starts, so a failed or cancelled search cannot be repeated at once. Columns the world generator puts in an ocean or river are skipped without loading their chunk.
- **Cooldowns:** `/tpa` and `/home` share the same default cooldown of 3 seconds (`tpa.cooldown_seconds` was 10, `homes.cooldown_seconds` was 5). Existing settings files keep their values.

## 1.0.1 (not released on its own; included in 1.1.0)

- **Cooldowns:** `/tpa` and `/home` default to 3 seconds.

## 1.0.0

First release. Requires MejtaCore 1.3.0 or newer, below 2.0.0 (API version 4); built and tested against MejtaCore 1.4.0.

- **Teleports:** `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle`, `/back` (also to your death point), `/spawn` and `/setspawn`. All share a wait that is cancelled by moving or damage, a combat lock, per-feature cooldowns and an optional cross-dimension rule.
- **Homes:** `/sethome`, `/home`, `/delhome` and `/homes` with a chest menu; a configurable limit, with a higher one for a permission. Staff can list, visit and delete other players' homes, also offline.
- **Warps:** `/warp`, `/warps` with a chest menu, `/setwarp` (the held item becomes the icon) and `/delwarp`.
- **Random teleport (experimental):** `/rtp` with a safe-spot search inside the world border.
- **Anywhere:** your ender chest (`/ec`), crafting table, anvil, stonecutter, grindstone, smithing table, loom, cartography table and a trash bin.
- **AFK:** `/afk`, automatic AFK, a broadcast, an `[AFK]` tag in the player list and an optional idle kick.
- **Chat:** `/r` for private messages, a join greeting with a first-join announcement, and death coordinates with a **[Back]** button.
- `/qol` lists the commands a player can use. Every feature can be switched off; its commands then disappear from players' command lists.
- Vanished players (through MejtaCore's presence service) are never found by `/tpa` and never announced as AFK.
