# MejtaUtils changelog

## 1.0.0

First release. Requires MejtaCore 1.3.0 or newer, below 2.0.0 (API version 4); built and tested against MejtaCore 1.4.0.

- `/invsee` and `/ecsee` for online and offline players, view-only or with editing. Every change is audited.
  - **Offline:** edits are queued and applied when the player next joins. This is experimental and off by default.
  - **Removed items:** they wait for the staff member in `/invsee claims`.
- `/status`: a player status panel that works for vanilla clients.
- `/vanish` hides the player from the tab list, entity tracking, join and leave messages, the server list, chat completion and other places. The README lists each one and what is not covered.
- `/freeze` and `/unfreeze`, kept across relogs and restarts.
- `/staffmode`: the player's inventory, game mode and position are saved, and restored on exit. This includes recovery after a crash at any step.
- Everything goes through MejtaCore: permissions, settings, audit, notifications, data store and presence.

Fixes from the release review, before the first release:
- **Invsee:**
  - `/invsee <self> edit` could duplicate items, so it now opens read-only.
  - Edit menus close when the target dies or enters staff mode.
- **Offline changes and staff-mode recovery:** they now run once the joining player is in the player list, and that player is saved before the change is marked done. A crash can no longer duplicate or lose items.
- **Staff mode:**
  - A retried exit can't hand items back twice.
  - The player's XP is set to zero on entry (and restored on exit), and nothing drops if they die in staff mode.
  - Claiming items in staff mode is refused.
  - Staff mode no longer drops vanish's night vision and flight speed: it clears effects, then re-applies vanish's.
- **Vanish:** the leave message no longer leaks when `vanish.persist_across_relog` is false.
- **Freeze:** commands typed in chat were not filtered, only commands run by other code. Every command a frozen player runs is now checked against `freeze.allowed_commands`.
- **Single-player and LAN:** state is kept per world, so it no longer carries over between worlds.
