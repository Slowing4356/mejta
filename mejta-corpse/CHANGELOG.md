# MejtaCorpse changelog

## 1.1.0

- **Visible armour:** the corpse wears the armour it holds, so you can see it. It is a display copy: the items stay in the corpse, killing the body drops nothing, dispensers cannot equip it, and a piece taken from the menu also leaves the body. New setting `display.show_armor` (default on).
- **Faster deaths:** a death now saves only the dying player, not every online player (with 50 players, that was about 150 file writes per death).
- **Tests:** a client game test with a real player covers dying, right-clicking the corpse, taking items and the armour on the body.

## 1.0.0

First release. Requires MejtaCore 1.3.0 or newer, below 2.0.0 (API version 4); built and tested against MejtaCore 1.4.0.

- **Corpses:** a dying player's items go into a corpse where they died, shown as a vanilla mannequin lying down in their skin. Players need no mod.
- **Menu:** right-clicking opens a chest menu laid out like the inventory screen.
  - **Take all** gives the owner every item back in its old slot, armour included.
  - Items can be taken but never put in.
- **Access:** owner-only by default, with a setting to make corpses public after a delay. Staff can open any corpse; owners can open void corpses from anywhere.
- **Duplication-safe:**
  - At death, the corpse is written first, then the items leave the inventory and the player is saved.
  - Every removal from a corpse is on disk before the item is handed out.
  - If a stop interrupts a death, it is resolved at the owner's next join.
- **Self-repairing:** killed corpse entities are put back; stray ones are removed.
- **Other features:** `/corpse` commands, optional expiry (drop or delete), and audit entries for looting and staff actions.
