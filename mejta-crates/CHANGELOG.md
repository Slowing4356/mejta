# MejtaCrates changelog

## 1.1.0

Requires MejtaCore 1.9.0 or newer, below 2.0.0 (API version 7).

- **Crate menus** use MejtaCore's shared look: the crate list gets a bottom bar with an info icon and Close, and the reward preview gets the standard Back, page and Close buttons; gray panes fill the empty slots.
- Opening a crate from the menu goes straight to the spin without closing the chest first, so the cursor stays where it was; with the animation off, the menu stays open with the new key count.

## 1.0.0

First release. Requires MejtaCore 1.8.0 or newer, below 2.0.0 (API version 6).

- **Crates** with weighted rewards of items, console commands and money (through MejtaCore's economy; money rewards are left out while no economy addon is installed). Edited in game with `/crates admin`, or by hand in `mejta-data/mejtacrates/store/crates/`.
- **Keys:** virtual keys (a count per player) and physical keys (items that can be traded). `/crates give|giveall|take` for staff and the console, so donation stores can sell keys; works for offline players and for names that have never joined.
- **Opening:** a spinning animation in a server menu (a MejtaCore screen, or a chest for vanilla clients) with sounds and a skip button; a preview with each reward's chance; crate blocks to open crates in the world.
- **Duplication-safe:** the key is taken and the prize recorded on disk before the animation; the prize is handed out once, also after a disconnect or a crash (at the next join). Physical keys carry a batch serial, so copies are caught and taken away.
- **Audited:** keys given, taken and claimed, copied keys, openings and crate edits.
- Example crate on first start; settings in MejtaCore's settings editor.
