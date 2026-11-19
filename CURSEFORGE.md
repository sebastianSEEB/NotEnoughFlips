Not Enough Flips is a SkyBlock trading helper. It puts a fair price on every item you hover over, finds auction, bazaar and craft flips, and alerts you when something is listed far below its usual price. It only shows you information. It never buys, bids or clicks for you.

## What to expect

I went from 1M to 100M coins in two days using only this mod, with no money-making guides. Your results will depend on how much you play, how many coins you start with and what the market is doing that day. Flips can lose money too, which is why every flip in the list shows how confident the mod is in it.

## Prices in tooltips

Hover over an item to see its average price, the lowest BIN, the 7-day trend and a verdict: GOOD BUY, FAIR PRICE or OVERPRICED. An item is only compared with identical copies, meaning the same stars, recombobulator, hot potato books, big enchants, gems, attributes, skin and scrolls. When an item's sales are too erratic to price, the tooltip says NO RELIABLE PRICE instead of guessing.

## Flip finder

Press E and click FLIP, to the right of your inventory. The panel has five tabs:

- AH: auction flips, priced at what the item actually sells for rather than at one lucky sale.
- Bazaar: live order flips on products that buy and sell quickly. Profit per hour takes into account the other orders competing at the top of the book.
- Craft: recipes that sell for more than the ingredients cost.
- Snipe: everything that can be sniped right now.
- Profit: what you have made so far.

## Snipe alerts

When a BIN is listed far below the lowest BIN (50% by default, and you can change it), a banner appears at the top of the screen. There are no alerts for markets that look manipulated or erratic, for items that are usually cheap anyway (skins, dyes, runes, cakes and similar), for low-value items or for items that sell slowly.

Click the banner to open the auction, or set the mod to open it for you. If the deal is profitable, the Buy button is highlighted. Pressing it is up to you.

## Bidder check

In an auction, a panel shows how many coins each of the other bidders has in purse and bank. If none of them can afford to go much higher, you don't need to open with a big bid.

## Bazaar signals

The mod compares each bazaar product with the products it usually moves with and points out when one has drifted out of line. It also watches pair spreads, enchanted-versus-raw recipe ratios and market-wide moves, and explains each signal in a sentence.

## Forecasts

Bazaar price forecasts for 1, 6 and 24 hours ahead. Each product gets the forecasting method that fits its price history, and a method is dropped when it stops being accurate. Turn forecasts off with `/nef forecast off`.

## Trade Desk

`/nef desk` opens an overview of your trading in a normal Minecraft-style menu:

- Home: your cash, coins on the AH, coins to collect and today's profit, a to-do list (items to collect, outbid bids, listings priced too high) and what ends next.
- My Auctions, Bids and Listings: your auctions and bids, with what each item is worth.
- Coins: what your coins add up to once everything settles, where they are right now, how they changed over the last week, what your open auctions are worth, and your profit today, this week and this month.
- Bazaar: prices with 7-day charts, the bazaar signals, and how many coins are traded per day on the bazaar and the AH.

Click a row to open that auction.

## Getting started

1. Install Fabric Loader and Fabric API.
2. Put the jar for your Minecraft version in your `mods` folder.
3. Join SkyBlock, press E, click FLIP and answer the three setup questions: your budget per flip, how much risk you accept, and how long you're willing to wait for a sale.

The bidder check needs a Hypixel API key, which you can get for free at developer.hypixel.net. Add it with `/nef apikey <key>`. It is saved only in your own config file.

## Commands

| Command | What it does |
|---|---|
| `/nef <item>` | Price card for an item, or for the one in your hand |
| `/nef flips` · `/nef bzflips` | Best auction and bazaar flips for your budget |
| `/nef snipe` | Snipe alert settings: `on`/`off`, `min 50`, `max 20m`, `auto on`/`off`, `block <ID>` |
| `/nef signals` | Bazaar misprices and correlations |
| `/nef forecast on\|off` | Use forecasts in the flip lists |
| `/nef desk` | Open the Trade Desk |
| `/nef mode simple\|pro` | Short or detailed tooltips |
| `/nef apikey <key\|clear>` | Your Hypixel API key, for the bidder check |

## FAQ

**Does it buy things for me?**
No. It never sends purchases, bids or clicks. The most it does is open an auction page or fill in a bazaar search, and you press every button.

**Where does the data come from?**
The public Hypixel API, SkyCofl price history, and an open data site that is rebuilt every hour from those APIs. The mod collects no player data.

**Why does an item say NO RELIABLE PRICE?**
Its recent sales are spread so widely that any average would be misleading.

**Why are the forecasts empty?**
A product needs about 12 hours of hourly history before it gets a forecast. They appear on their own.

## Fair play

The mod reads public market data and displays it. Using any mod on a server is at your own risk, so check the server's rules on allowed modifications.

---

<sub>Open source (MIT with credit): [GitHub](https://github.com/sebastianSEEB/NotEnoughFlips) · [Report a bug](https://github.com/sebastianSEEB/NotEnoughFlips/issues) · Not affiliated with Hypixel, Mojang or Microsoft.</sub>
