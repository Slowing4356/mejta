# MejtaMarket

An auction house for Fabric servers, as a MejtaCore addon (mod ID `mejtamarket`). Players sell what they hold with `/ah sell`, browse and buy in a menu, place buy orders that others fill from their inventory, and pick up bought, unsold and ordered items from a collection box. A **market bot** buys and sells a little now and then, so the market keeps moving when only two or three players are online.

- **Server-side only.** Players need no mods: the market is a chest menu, and a screen for players with MejtaCore on their client.
- **Paid through MejtaCore's shared economy.** Install an economy addon such as [MejtaEconomy](../mejta-economy/README.md); without one the market stays closed.
- **Items can never be duplicated.** Not by clicking, not by two players at once, not by a crash. See [How duplication is prevented](#how-duplication-is-prevented).
- **Fees take money out of the economy**: a listing fee and a sales tax, both adjustable.

| | |
|---|---|
| Version | 1.2.0 |
| Requires | MejtaCore 1.10.0 or newer, below 2.0.0 (API version 8), an economy addon (MejtaEconomy), Fabric API |
| Minecraft | 26.1.2, Fabric Loader 0.19.5+, Java 25 |

## Installing

Put `mejtamarket-1.2.0.jar` next to `mejtacore-1.10.0.jar`, `mejtaeconomy-1.0.0.jar` and Fabric API in the server's `mods/` folder. Settings appear in `config/mejta/mejtamarket.json`, the bot's price list in `config/mejta/mejtamarket-prices.json`.

## Using the market

| Command | Permission | What it does |
|---|---|---|
| `/ah` (or `/market`) | `mejtamarket.use` | Opens the auction house |
| `/ah search [text]` | `mejtamarket.use` | Opens it showing only matches (item name, ID or seller; `iron pick` finds iron pickaxes). Without text, a sign opens to type the search on. |
| `/ah sell [price] [amount]` | `mejtamarket.sell` | Lists what you hold in your main hand (or `amount` of it) for `price` in all, e.g. `/ah sell 2k 32`. Without a price, a sign opens to type it on, showing the market price. |
| `/ah order <amount> <price each> [item]` | `mejtamarket.orders` | Places a buy order, e.g. `/ah order 64 5 oak_log`. Holding the item, leave out its name. The money is set aside now. |
| `/ah orders` | `mejtamarket.use` | Buy orders you can sell into |
| `/ah collect` | `mejtamarket.use` | Your collection box |
| `/ah listings` | `mejtamarket.use` | Your listings |
| `/ah price [item]` | `mejtamarket.use` | The item's market price and its last player trades |
| `/ah help` | `mejtamarket.use` | Lists these commands |

Prices accept what the economy reads: `50`, `12.5`, `2k`.

**The auction house menu** shows 45 listings per page, each with its price (and price per item), seller, time left and market price. The bottom row has: previous page, **category** (left/right click to switch: blocks, ores and minerals, farming and food, mob drops, tools and weapons, armour, redstone, potions and enchanting, other), **search** (click to type on a sign, right click to clear), **sort** (newest, cheapest or most expensive per item), the **collection box**, **your listings**, **buy orders**, **sell** (shows what you hold and its market price; click to type a price on a sign) and next page.

The search and price signs work on vanilla clients: the sign only exists for you, and the block is restored when you are done. If nothing matches a search for an item on the price list, the menu says the market bot will bring some; it usually does within a minute (see below).

- **Buying:** click a listing and confirm. The items go to your inventory, or to your collection box if it is full.
- **Cancelling:** click your own listing and confirm; the items go to your collection box. The listing fee is not refunded.
- **Expiry:** listings end after 48 hours (`listing.duration_hours`); unsold items go back to the seller's collection box. Buy orders end after 7 days and refund what was not spent.
- **Buy orders:** click an order to sell your items into it (after a confirmation showing what you get after tax). Only plain items count: no enchantments, custom names or damage. The items go to the order owner's collection box. Click your own order to cancel it; the unspent money comes back.
- **Collection box:** bought, returned and ordered items wait here until you take them, with no time limit. Money the market could not pay you at once (e.g. your balance would go over the economy's limit) waits here too. You are told when you join if something is waiting.

Sellers and order owners who are online are told when something sells or an order is filled; offline sellers are paid all the same.

## Fees

| | Default | |
|---|---|---|
| Listing fee | 1 % | Of the price (or of a buy order's total), paid when listing; never refunded |
| Sales tax | 5 % | Kept back from what the seller (or whoever fills a buy order) gets |

Both leave the economy, so trading slowly drains money instead of creating it.

## The market bot

On a server with two players, a market without anyone else to trade with is dead. The bot ("Market Trader") is that someone else, built so it cannot be farmed and cannot flood the economy with money.

**What it does.** Every 4 to 12 minutes (at random), it may do one small thing:

- **buy a player's listing** that costs at most 75 % of the item's market price;
- **sell into a player's buy order** that pays at least 115 % of the market price;
- **list** a quarter stack to a full stack of an item for 115 % of its market price;
- **place a buy order** for an item at 75 % of its market price.

**It follows demand.** Every search is remembered per item (one count per player and item every 10 minutes; interest halves every 6 hours). The bot lists items players looked for more often, up to nine times as often as an item nobody searches for. When someone searches for a price-list item that nobody sells and the bot still has stock of it today, the bot lists some within 20 to 60 seconds (if it would be active at the current player count) and tells that player, with a button to show it. If it is at its listing limit, it first withdraws its least wanted listing. Demand is saved in the market data, so it survives restarts.

With up to 3 players online (`bot.full_activity_players`, not counting AFK or vanished players) it always acts; from 10 (`bot.max_players`) it stops; in between it acts less and less often. It only trades items on the **price list**, and only plain items.

**Market prices.** Each item's market price starts at its price-list price and follows what players really pay each other:

- only trades between two different players count, never the bot's;
- all trades between the same buyer and seller on one day count as one sample, so two accounts trading back and forth add one sample a day;
- trades count once there are 3 such samples (`prices.min_samples`) in the last 14 days (`prices.history_days`); the highest and lowest fifth are dropped, and the median is taken;
- the market price moves at most 10 % per day (`prices.max_daily_change_percent`) and stays between half and twice the price-list price (`prices.band`).

**No arbitrage.** The bot always pays less than it asks (75 % against 115 % of the same price), and players also pay the sales tax. Buying from the bot to sell back to it always loses money. Pushing the market price up with fake trades takes many days of taxed trades between different accounts, and the bot's daily budget caps what could ever be taken from it.

**No inflation.** The bot's money comes from a budget of 5,000 per day (`bot.daily_budget`), saved up to 20,000 at most (`bot.budget_cap`). This is the only money it adds to the economy. Money players pay the bot is not added to its budget: it leaves the economy. Items the bot buys are destroyed, and it sells at most 128 of each item per day (`bot.daily_stock_per_item`). On most servers the bot takes more money out than it puts in; `/mejtaadmin mejtamarket bot` shows both sides.

**The price list** (`config/mejta/mejtamarket-prices.json`) holds a price in whole units of the currency per item:

```json
{
  "items": {
    "minecraft:diamond": 100,
    "minecraft:iron_ingot": 12,
    "minecraft:oak_log": 2
  }
}
```

The defaults cover about 50 common goods (building blocks, ores, crops and food, mob drops). Rare items such as elytra, netherite, totems and spawners are left out on purpose. Edit the file and run `/mejtaadmin mejtamarket prices reload`, or hold an item and use `/mejtaadmin mejtamarket prices set <price>` (or `... prices remove`). Remove an item to stop the bot trading it. Set `bot.enabled` to `false` to switch the bot off.

## Staff commands

| Command | What it does |
|---|---|
| `/mejtaadmin mejtamarket status` | Listings, buy orders, the price list's size and the economy in use |
| `/mejtaadmin mejtamarket bot` | The bot's budget, what it paid players and what it received, and the net effect on the economy |
| `/mejtaadmin mejtamarket bot run` | Makes the bot do one thing now, whatever the number of players |
| `/mejtaadmin mejtamarket prices` | The price list, with each item's current market price |
| `/mejtaadmin mejtamarket prices set <price> [item]` | Sets an item's price-list price (the held item if none is named) |
| `/mejtaadmin mejtamarket prices remove [item]` | Takes an item off the price list |
| `/mejtaadmin mejtamarket prices reload` | Re-reads the price list file |
| `/mejtaadmin mejtamarket remove <id>` | Removes a listing (items back to the seller) or a buy order (money back to the owner) |

In the menus, staff can also **shift-right-click** someone else's listing or buy order to remove it. Removals and price-list changes are audited.

## Permissions

| Node | Default level | Allows |
|---|---|---|
| `mejtamarket.use` | 0 (everyone) | `/ah`, buying, the collection box |
| `mejtamarket.sell` | 0 (everyone) | `/ah sell` |
| `mejtamarket.orders` | 0 (everyone) | Placing buy orders and selling into them |
| `mejtamarket.admin` | 3 (admins) | Staff commands, removing anyone's listings and orders |

## Settings

Stored in `config/mejta/mejtamarket.json`. Change them in the file, in MejtaCore's settings editor or chest menu (`/mejtaadmin config menu mejtamarket`), or with `/mejtaadmin config set mejtamarket <key> <value>`. Amounts of money are in whole units of the currency.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | The market is open. Off: nobody can list, buy or fill, but everyone can still collect. |
| `listing.max_per_player` | `10` (1–200) | Listings a Member (a player without a rank) may have on sale at once |
| `listing.max_per_player_bronze`, `_silver`, `_gold` | `13`, `16`, `20` (1–200) | The same for Bronze, Silver and Gold players ([MejtaCore ranks](../mejta-core/README.md#ranks)) |
| `listing.duration_hours` | `48` (1–720) | Hours a listing stays on sale |
| `listing.min_price` | `1` | Lowest price of a listing, or of one item in a buy order |
| `listing.max_price` | `10000000` | Highest price of a listing or a whole buy order |
| `fees.listing_percent` | `1.0` (0–25) | Listing fee, in percent of the price |
| `fees.sales_tax_percent` | `5.0` (0–50) | Sales tax, in percent of each sale |
| `orders.enabled` | `true` | Buy orders are available |
| `orders.max_per_player` | `5` (1–100) | Open buy orders per player |
| `orders.duration_hours` | `168` (1–2160) | Hours a buy order stays open |
| `orders.max_amount` | `2304` | Most items one buy order may ask for |
| `notify.sales` | `true` | Tell online players when their items sell or their orders are filled |
| `audit.trades` | `true` | Record every trade in MejtaCore's audit log too |
| `bot.enabled` | `true` | The market bot is active |
| `bot.name` | `Market Trader` | The bot's name in the market |
| `bot.interval_min_minutes`, `bot.interval_max_minutes` | `4`, `12` | Time between two bot actions, picked at random in between |
| `bot.full_activity_players` | `3` | Up to this many active players, the bot always acts |
| `bot.max_players` | `10` | From this many active players, the bot stops |
| `bot.buy_factor` | `0.75` (0.1–0.95) | The bot pays at most this share of the market price |
| `bot.sell_factor` | `1.15` (1.05–5) | The bot asks at least this multiple of the market price |
| `bot.daily_budget` | `5000` | Money per day the bot may spend (the only money it adds) |
| `bot.budget_cap` | `20000` | Most money it can save up |
| `bot.daily_stock_per_item` | `128` | Most items of one kind it sells per day |
| `bot.max_listings`, `bot.max_orders` | `12`, `6` | Listings and buy orders it keeps open at most |
| `prices.history_days` | `14` | Days of player trades used for market prices |
| `prices.min_samples` | `3` | Samples needed before trades count |
| `prices.max_daily_change_percent` | `10.0` | How far a market price may move per day |
| `prices.band` | `2.0` | Market prices stay between the price-list price divided and multiplied by this |

## How duplication is prevented

The stored record of a listing (`mejta-data/mejtamarket/store/lots/<id>.json`) is the only copy of its items. Every step that moves items is on disk, with a forced flush, before the game can see the change:

1. **Listing and selling into an order.** The listing is written as **pending**, holding the items. Then the items leave the inventory and **the player is saved** (only that player, with the game's own save). Then the listing goes on sale. If the server stops in between, the player's next join decides: if they still hold the items, the listing is dropped; if not, it goes on sale (or the order is filled). If the listing or the player cannot be saved, nothing is taken.
2. **Buying.** Everything runs on the server thread, one click at a time, so two players can never buy the same listing. The buyer pays, then the listing is written as sold (if that write fails, the buyer is refunded at once), then the seller is paid.
3. **Collecting.** The record is written without the items (or as gone) before they are handed out. If that write fails, nothing is handed out. Only what fits in the inventory is handed out; the rest stays in the box.
4. **Money is written before it is paid.** Orders are closed on disk before their refund, payouts are marked paid before the money moves. A crash can lose a payment in progress, never make it twice.
5. **Menus hold no items.** Every icon is a copy made for display; nothing can be taken out of or put into the market's menus.

## Data and logs

- **Records:** `mejta-data/mejtamarket/store/` holds `lots/`, `orders/` and `payouts/` (one small JSON file each), `prices.json` (recent trades and market prices) and `bot.json` (the bot's budget and totals). On a singleplayer or LAN world they are kept per world (under `store/worlds/<id>/`).
- **Trade log:** `mejta-data/mejtamarket/trades.jsonl`: every listing, sale, fill, cancellation, collection and bot action, kept for a year.
- **Audit log:** sales, fills, buy orders and their ending (with `audit.trades`), and staff removals and price-list changes (always), in MejtaCore's audit log.

## Building

```sh
cd ../mejta-core && ./gradlew publishToMavenLocal
cd ../mejta-market && ./gradlew build          # jar in build/libs/, runs the unit tests
./gradlew runGameTest                           # headless server test with fake players and a test economy
```

Unit tests cover fees, categories and search, market prices (samples, the daily limit, the band, outliers), the bot's prices, activity and budget, search demand, and the stored records. The game test lists, buys, fills a buy order, refuses a listing whose save fails, lets the bot buy a listing and records a search as demand, checking every item and coin after each step.
