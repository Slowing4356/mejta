# MejtaQoL changelog

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
