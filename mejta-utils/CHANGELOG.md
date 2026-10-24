# MejtaUtils changelog

## 1.1.1

- **The cursor stays put:** taking a claim redraws the claims list in place instead of closing and reopening it, so the cursor stays where it was.

## 1.1.0

Requires MejtaCore 1.9.0 or newer, below 2.0.0 (API version 7).

- **Staff menus** use MejtaCore's shared look: `/vanish` settings and `/status` get gray panes and a Close button; the game mode picker gets a Back button to the status panel and says which mode is current.
- **`/invsee claims` has pages,** so every claim is listed (it stopped at 53), with an info icon explaining where the items come from.

## 1.0.2

- **Op bypass switch:** `mejtautils.freeze.exempt` now only applies while the new setting `op_bypass.enabled` is on (off by default). Players with `mejtautils.protected` (owners by default) can still only be frozen by staff who have it too.

## 1.0.1

- **Faster joins:** a joining player's vanish state is now read from disk once, when their connection is set up. Before, it was read for each check during the join, up to about 100 times with 50 players online.

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
