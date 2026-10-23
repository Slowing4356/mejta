# MejtaMarket changelog

## 1.2.0

Requires MejtaCore 1.10.0 or newer, below 2.0.0 (API version 8).

- **Rank perks:** players with a MejtaCore rank may have more listings on sale at once: `listing.max_per_player_bronze`, `_silver` and `_gold` (13, 16 and 20; Members keep `listing.max_per_player`, 10). Fees, prices and the bot are the same for everyone.

## 1.1.0

- **Search on a sign:** the menu's search button (and `/ah search` without text) opens a sign to type the search on, on vanilla clients too. Right click the button to clear a search. An empty result says when the bot will bring the item.
- **Sell from the menu:** the help button is now a sell button showing what you hold and its market price; click it (or use `/ah sell` without a price) to type a price on a sign.
- **The bot follows demand:** searches are remembered per item (fading over 6 hours, one count per player every 10 minutes). The bot lists wanted items more often, and lists an item someone searched for that nobody sells within a minute, telling them. At its listing limit it withdraws its least wanted listing first. Budget, stock and price rules are unchanged.

## 1.0.0

First release. Requires MejtaCore 1.8.0 or newer, below 2.0.0 (API version 6), and an economy addon such as MejtaEconomy.

- **Auction house** (`/ah`, `/market`): sell what you hold with `/ah sell <price> [amount]`; browse, search, filter by category and sort in a menu (a chest, or a screen with MejtaCore on the client); buy with a confirmation; cancel your own listings. Listings expire after 48 hours.
- **Buy orders:** `/ah order <amount> <price each> [item]` sets the money aside; others sell plain items into it from the menu; unspent money comes back when it is cancelled or expires.
- **Collection box:** bought, returned and ordered items, and payments that could not be made at once, wait until collected.
- **Fees:** a listing fee (1 %) and a sales tax (5 %) that leave the economy.
- **Market bot:** buys cheap listings, sells into generous buy orders, and keeps a few listings and orders of its own, so a market with two players keeps moving. Market prices come from real player trades (one sample per buyer, seller and day; trimmed median; at most 10 % a day; within a band around the price list). It buys at 75 % and sells at 115 % of the market price, spends at most a daily budget, destroys what it buys and sells limited stock per day, so it cannot be farmed and takes more money out of the economy than it adds. Fades out as more players come online. Price list in `config/mejta/mejtamarket-prices.json`.
- **Duplication-safe:** listings and fills are written as pending before the items leave the inventory and the player is saved, then finished (or resolved at the next join after a crash); items are written out of the market before they are handed out; money is recorded before it is paid.
- **Logs:** every trade in `trades.jsonl`, sales and staff actions in MejtaCore's audit log.
