package dev.notenoughflips;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** .minecraft/config/nef.json — created with defaults on first launch. */
final class NefConfig {
	boolean enabled = true;

	/** Optional: your own local price server (advanced). Empty = not used. */
	String localServer = "";
	/** Public data site (bazaar signals, craft flips, bazaar history + forecasts), rebuilt by GitHub Actions. */
	String dataUrl = "https://sebastianseeb.github.io/NotEnoughFlips/data";
	/**
	 * Your own Hypixel API key, only for the bidders' coins panel (/nef apikey <key>). Stored in this file on your PC and
	 * only ever sent to api.hypixel.net. Leave empty to skip that panel.
	 */
	String hypixelApiKey = "";
	/** Live bazaar prices straight from the public Hypixel API (no key needed). */
	boolean useHypixelApi = true;
	String hypixelBazaarUrl = "https://api.hypixel.net/v2/skyblock/bazaar";

	int localRefreshSeconds = 30;
	int publishedRefreshSeconds = 600;
	int liveBazaarRefreshSeconds = 20;

	boolean valueUpgrades = true;    // value each copy: enchants, stars, recomb (rarity), potato books, gems, pet level/rarity/item/skin
	boolean showVerdict = true;      // "GOOD BUY / FAIR PRICE / OVERPRICED" line under the average
	boolean showFlipBanner = true;   // bold "PROFIT FLIP / WORTH BIDDING" line right under the item name
	double minFlipProfitPercent = 5; // profit after AH fees needed before an item is marked as a flip
	double maxBidPercentOfLowestBin = 70; // preset bid = lowest valid bid (current + 2.5 %), never above this % of lowest BIN
	boolean copyBidOnClick = true;
	boolean watchBids = true;         // auctions you click (bid copied) go on a watch list
	int watchRecheckSeconds = 30;     // how often the watch list re-checks lowest BIN
	int watchHours = 48;              // watched auctions expire after this

	boolean outbidHud = true;         // panel right of your inventory with auctions you've been outbid on (click = open)
	int outbidHours = 12;
	boolean modScan = true;           // scan the AH for your listings + bids every 15 min while you play (FCF panel / Trade Desk)
	boolean coflFallback = true;      // use SkyCofl (sky.coflnet.com) prices when our data has too little history
	boolean fcfButton = true;
	// bazaar orders HUD (top right, also without the inventory open): toggle with the key (default O) or /nef orders
	boolean bzHud = false;
	int bzHudMax = 5;                 // orders shown per side (buy | sell)
	String bzHudSize = "medium";      // small | medium | large (width)
	boolean eventsButton = true;      // "EVENTS" button above your inventory: upcoming SkyBlock events and seasonal buy / sell prompts
	boolean seasonAlerts = true;      // chat line when a seasonal product's buy or sell window opens
	boolean dealsButton = true;        // "DEALS" button above your inventory: /nef deals as a clickable list on the left
	String bazaarSort = "hour";       // FLIP → Bazaar → Best flips order: hour (profit/h), profit, pct (margin %), volume
	boolean discordButton = true;      // small "Discord" button at the bottom right of your inventory
	boolean deskButton = true;         // "DESK" button above your inventory: the Trade Desk (positions, bazaar, signals, coach)
	/** Bazaar products shown in the Trade Desk "Bazaar" section and the FCF panel's Bazaar tab (/nef bz add|remove <id>). */
	java.util.List<String> bazaarWatch = new java.util.ArrayList<>(java.util.Arrays.asList(Desk.DEFAULT_BAZAAR));         // "FCF" button above your inventory: potential free cash flow panel
	boolean highlightDeals = true;    // yellow border on AH auctions worth buying/bidding (glow when >= 20 % under fair)
	boolean reportPurse = false;      // send your purse (from the sidebar) to your own local server (advanced; off)
	int purseReportSeconds = 300;
	boolean dealAlerts = true;        // chat alert for new underpriced BINs found by your own AH scan (or a local server)
	double dealMinDiscountPercent = 20;
	boolean npcFlips = true;          // items listed / sold for less than an NPC pays: DEALS list + chat alert
	double npcFlipMinProfit = 50_000; // smallest NPC flip worth telling you about (coins)
	boolean fairySouls = true;
	boolean fairySoulCorner = true;
	boolean dungeonChestProfit = true; // dungeon reward chests: profit of each, best one outlined, whether a key pays
	boolean dungeonMap = true;        // dungeon map from your hotbar as a corner minimap (only what that map shows)
	int dungeonMapSize = 100;         // minimap size in GUI pixels   // Fairy Soul count in the bottom-right corner of your inventory        // markers for the Fairy Souls on your island you haven't clicked yet (/nef souls)
	boolean tradeCheck = true;        // player trade menu: what each side is worth and the difference
	boolean listPriceFill = true;     // Create BIN Auction: show a suggested price and fill it into the price sign
	boolean undercutAlerts = true;    // chat alert when someone lists a copy as good as yours for less than your BIN
	double minItemValue = 1_000_000;  // AH: ignore items worth less than this everywhere (flips, deals, snipes, tooltip banners, gold frames); /nef minvalue
	double dealMinValue = 1_000_000;
	int dealPollSeconds = 60;   // clicking an auction copies the target bid to your clipboard (you place the bid yourself)
	// ---- flipping (set by the 30-second setup in the FLIP panel; /nef setup to redo it)
	boolean setupDone = false;
	boolean simpleMode = true;        // Simple: tooltips show only what to do. Pro: every number (/nef mode pro)
	String risk = "normal";           // safe / normal / bold (sets minFlipProfitPercent + minSalesPerDay)
	double budgetPercent = 25;        // most of your purse one flip may use
	double fallbackBudget = 1_000_000;// budget when the purse can't be read from the sidebar
	double minSalesPerDay = 4;        // items that sell less often are never flagged as flips
	double maxHoldHours = 8;          // longest you're willing to wait for a resale
	double minFlipProfitCoins = 20_000;
	boolean flipButton = true;        // "FLIP" tab right of your inventory / chests: top flips that fit your purse
	boolean flipFeed = true;          // the mod's AH scan also looks for underpriced BINs (gap to the next-cheapest copy)
	int scanMinutes = 15;             // how often the mod scans the whole AH while you play (min 5)
	boolean flipAlerts = true;        // chat alert for the best new flips found by the scan (max 3 per scan)
	boolean alertSound = true;        // ping on: item sold, outbid, bazaar order filled / outbid, big new flip
	double bazaarTaxPercent = 1.25;   // 1.25 % default; lower with the Bazaar Flipper account upgrade
	double bazaarSharePercent = 10;   // part of a product's traded volume you can expect to fill
	boolean bazaarBookWatch = true;   // watch the order books: how often the top order is outbid / the spread holds (BzWatch)
	double bazaarCheckMinutes = 5;    // how often you re-check (and re-outbid) your bazaar orders
	boolean priceChart = true;        // big 7-day price chart beside the menu while you hover an auction item
	boolean manipMarkers = true;      // red "!" on AH items that look manipulated (price spike, buyout, inflated listings)
	boolean signFill = true;          // pre-fill the Bazaar search / amount / price signs for the flip you clicked (you press Done)
	int bazaarRefreshMinutes = 0;     // 0 = live (re-ranked on every bazaar update); > 0 = a snapshot refreshed this often
	double bazaarRoundHours = 2;      // one flip round (buy order fills + sell offer sells) should take at most this long
	double bazaarMaxSpreadPercent = 60;// wider spreads are thin / manipulated markets: never suggested
	boolean bazaarOrderTracker = true;// read "Your Bazaar Orders" and warn when an order is outbid / undercut
	boolean profitTracker = true;     // log buys + sells from chat → realized profit per flip (config/nef-trades.csv)
	String lastSummaryDay = "";       // the "Yesterday: +X from N flips" message is shown once per day

	boolean showHistory = true;      // 7d / 30d / all-time lines
	boolean showForecast = true;
	/** Use the published price forecasts in the AH and Bazaar flip lists (FC chip in the FLIP panel, /nef forecast on|off). */
	boolean forecastFlips = true;
	boolean showSeasons = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Coins one flip may use: budgetPercent of your purse, or fallbackBudget if the purse is unknown. */
	double budget(Double purse) {
		return purse != null && purse > 0 ? purse * budgetPercent / 100.0 : fallbackBudget;
	}

	void applyRisk(String level) {
		risk = level;
		switch (level) {
			case "safe" -> { minFlipProfitPercent = 10; minSalesPerDay = 10; }
			case "bold" -> { minFlipProfitPercent = 3; minSalesPerDay = 1; }
			default -> { minFlipProfitPercent = 5; minSalesPerDay = 4; risk = "normal"; }
		}
	}

	void save() {
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("notenoughflips.json"), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			System.err.println("[NEF] could not save config: " + e);
		}
	}

	boolean sniper = true;            // banner + ping when an undervalued BIN is listed (needs flipFeed for the reference prices)
	double snipeMinPercent = 50;      // alert only when the BIN is at least this much under the lowest BIN of identical copies
	boolean craftLive = true;         // work out craft flips in the mod every minute (live prices, your recipes)
	boolean craftMineOnly = true;     // only show crafts whose recipe you have unlocked (needs /nef apikey to know)
	boolean endingAlerts = true;      // chat line ~2.5 min before a regular auction ends well under the item's value
	boolean snipeUpgrades = true;     // also snipe copies priced like a plain one but carrying upgrades worth much more
	boolean snipeBeats = true;        // count down a new BIN's hold with ticks (3, 2, 1) and a "go" sound at the moment it's buyable
	int snipeGraceMs = 20_000;        // how long Hypixel holds a new BIN before anyone can buy it
	int snipeBeatLeadMs = 40;         // play the beats this much early (sound takes a moment to reach you)
	boolean snipeAutoOpen = false;    // open the auction (/viewauction) by itself on an alert; off by default (a command without a keypress could look like a macro to Hypixel)
	// auto-open limits: an alert only opens the auction by itself when it is at least this far under the lowest BIN,
	// makes at least this much after fees, costs at most this much (0 = what's in your purse), and (if set) has passed
	// the sales-history check first. Everything else still shows the banner.
	double snipeOpenMinPercent = 50;
	double snipeOpenMinProfit = 1_000_000;
	double snipeOpenMaxPrice = 0;
	boolean snipeOpenChecked = false;
	double snipeMaxPrice = 0;         // never alert above this price (0 = whatever fits your purse)
	double snipeMinValue = 1_000_000; // skip items whose lowest BIN is under this (cheap junk gets relisted low all day)
	double snipeMinSalesPerDay = 2;   // skip items that barely sell (you'd be stuck holding them)
	java.util.List<String> snipeBlock = new java.util.ArrayList<>();   // your own blacklist (item IDs)
	java.util.List<String> snipeAllow = new java.util.ArrayList<>();   // items taken off the built-in blacklist     // open the auction (/viewauction) by itself when an alert fires; you still press Buy
	int cfgRev = 0;                   // settings migrations already applied

	/** One-time changes to existing settings when a new version changes a default the player never chose. */
	void migrate() {
		// rev 3: the data site moved to the NotEnoughFlips repo (the first builds saved the old address)
		if (dataUrl != null && dataUrl.contains("/not-enough-flips")) dataUrl = dataUrl.replace("/not-enough-flips", "/NotEnoughFlips");
		cfgRev = 3;
	}


	static NefConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("notenoughflips.json");
		NefConfig cfg = new NefConfig();
		try {
			if (Files.exists(file)) {
				NefConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), NefConfig.class);
				if (read != null) cfg = read;
			}
			cfg.migrate();
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			System.err.println("[NEF] could not read config, using defaults: " + e);
		}
		return cfg;
	}
}
