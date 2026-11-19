# Not Enough Flips

A Hypixel SkyBlock trading helper for Fabric. Not Enough Flips shows prices, verdicts, flips and alerts. **It never buys, bids or clicks for you.** Every trade is yours to make.

![icon](mod/src/main/resources/assets/notenoughflips/icon.png)

## Features

- **Tooltips:** average, lowest BIN, 7-day trend and a verdict (GOOD BUY / FAIR PRICE / OVERPRICED). The verdict only compares items with the same stars, recombs, enchants, gems, attributes and skins. If an item's sales are too erratic to price, it says **NO RELIABLE PRICE** instead of guessing.
- **FLIP panel** (press E, then click FLIP), with five tabs:
  - **AH:** flips priced against the clearing floor, not the cheapest outlier.
  - **Bazaar:** live order flips that both buy and sell fast. Profit/hour accounts for the orders competing near the top of the book.
  - **Craft:** recipe flips.
  - **Snipe:** a watch list of items that can be sniped right now.
  - **Profit:** your realised profit.
- **Snipe alerts:** a banner at the top of the screen when a BIN is listed at your chosen discount (default 50 %) under the lowest BIN.
  - It skips manipulated or erratic markets, a blacklist of usually-cheap items (skins, dyes, runes, cakes…), low-value items and slow sellers.
  - Optionally opens `/viewauction` for you. You still press Buy and Confirm.
  - The Buy button is highlighted when the deal is profitable.
- **Your data stays yours:** trades, profit, coins history, watched auctions and bazaar orders are stored per Minecraft account in `config/notenoughflips/accounts/<uuid>/`. Lowest BINs, upgrade prices, `/nef deals` and auction-house forecasts are worked out on your own PC from the mod's own AH scan.
- **Bid helper:** in an auction view, a panel shows how many coins the other bidders hold (purse + bank), so you don't overbid against someone who can't follow. This needs your own Hypixel API key: `/nef apikey <key>`, which also adds your bank balance to the Trade Desk. The key is stored only in your local config.
- **Bazaar signals:** misprices measured against each product's correlated peers, pair spreads, compression-recipe ratios and market-wide events, with plain-language suggestions.
- **Forecasts:** 1 h, 6 h and 24 h bazaar forecasts. The method is chosen per series (smoothing, Holt, regression, hour-of-day index), and a model is dropped when its tracking signal fails. Turn them on or off with `/nef forecast on|off`.
- **Trade Desk** (`/nef desk`): a simple ERP for your trading, with six sections:
  - **Home:** key numbers, a to-do list and what ends next.
  - **My Auctions:** everything you have on the AH.
  - **Bids:** auctions you bid on.
  - **Listings:** items you are selling.
  - **Coins:** what your coins add up to once everything settles, where they are right now, a 7-day history, what your open auctions are worth, and your profit.
  - **Bazaar:** prices, tips, and coins traded per day on each market (market size).

## Commands

| Command | What it does |
|---|---|
| `/nef <item>` | Price card for an item, or for the item in your hand |
| `/nef flips`, `/nef bzflips` | Best AH and bazaar flips for your budget |
| `/nef snipe [on\|off\|min N\|max 20m\|auto on\|off\|block ID\|unblock ID]` | Snipe alerts |
| `/nef signals` | Bazaar misprices and correlations |
| `/nef forecast on\|off` | Use forecasts in the flip lists |
| `/nef apikey <key\|clear>` | Your own Hypixel API key, for the bidders panel |
| `/nef desk` | Open the Trade Desk |
| `/nef orders` | Bazaar orders HUD on or off (also key O): `size small\|medium\|large`, `max <1-20>` |
| `/nef mode simple\|pro` | Short or detailed tooltips |

## Install

1. Install Fabric Loader and Fabric API for your Minecraft version (1.21.11, 26.1.x, 26.2.x or 26.3.x).
2. Download the matching `notenoughflips-…+mcXX.jar` from [Releases](../../releases) and put it in `.minecraft/mods`.

## Data

The mod reads public data only:

- the Hypixel API;
- [SkyCofl](https://sky.coflnet.com) price history;
- this repo's data site (`https://sebastianseeb.github.io/NotEnoughFlips/data`).

GitHub Actions rebuilds the data site from public APIs:

| Workflow | Runs | Builds |
|---|---|---|
| `data-bazaar.yml` | hourly | bazaar history and forecasts |
| `data-signals.yml` | every 2 h | bazaar signals |
| `data-crafts.yml` | every 3 h | craft flips |

No player data is collected or published.

To run your own data site: fork the repo, enable GitHub Pages on the `gh-pages` branch, and change `dataUrl` in `config/notenoughflips.json`.

## Building

```
cd mod && ./gradlew build                     # Minecraft 1.21.11
```

For 26.x builds, see `.github/workflows/build.yml`.

## License

MIT with an attribution requirement: if you use, modify or redistribute this code, credit **"Not Enough Flips by sebastianSEEB"** with a link to this repository. See [LICENSE](LICENSE).

Not affiliated with Hypixel, Mojang or Microsoft.
