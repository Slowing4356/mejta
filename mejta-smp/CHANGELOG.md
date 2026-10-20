# MejtaSMP changelog

## 1.0.0

First release. Requires MejtaCore 1.5.0 or newer, below 2.0.0 (API version 5).

- **AFK protection:** AFK players (MejtaCore's AFK status) cannot be hurt by other players, unless they are in combat. Optionally, mobs cannot hurt them either.
- **Scoreboards:**
  - each player's own sidebar (their stats, top kills or top play time), picked with `/sb`;
  - `/leaderboard` with the top 21 by kills, deaths, play time, mob kills or best kill streak;
  - an optional score in the player list.
- **Items and XP:**
  - XP orbs clump and are taken at once;
  - dropped items merge from farther away;
  - experimental piles bigger than a stack, labelled with their size and built so items are never duplicated.
- **World:** infinite villager trades, one-player sleep, closable Nether and End, and the locator bar hidden.
- **Account limit:** a cap on accounts online at once per internet address, and optionally on different accounts. Addresses are only kept as salted hashes.
