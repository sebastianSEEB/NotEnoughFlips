package dev.notenoughflips;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Not Enough Flips: adds market data to Hypixel SkyBlock item tooltips and a /nef command.
 * Data: an optional local server if running, otherwise the published history on GitHub Pages, plus
 * live bazaar prices from the public Hypixel API. Read-only: never sends anything to Hypixel's
 * game server and never automates anything.
 */
public final class NefClient implements ClientModInitializer {
	private static NefConfig config;
	private static DataClient data;

	static DataClient data() {
		return data;
	}
	static CoflClient cofl;
	private static final List<Component> LOADING = List.of(HudFormatter.line("Market: loading…", HudFormatter.GRAY));

	@Override
	public void onInitializeClient() {
		config = NefConfig.load();
		data = new DataClient(config);
		cofl = new CoflClient(config);
		Valuation.init(data);

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
			try {
				if (!config.enabled) return;
				String key = ItemKeys.keyFor(stack);
				boolean inBazaar = inBazaarMenu();
				if (key == null && inBazaar) key = Bazaar.idForName(stack.getHoverName().getString(), data);   // bazaar menu icons
				if (key == null) return;                       // not a SkyBlock item
				// yours: your own listing (Manage Auctions / Seller: you), or any item that isn't an auction listing
				Verdict.Mine mine = Verdict.ownListing(lines, stack.getCount(), playerName());
				Verdict.Listing listing = mine == null ? Verdict.listing(lines, stack.getCount()) : null;
				if (mine == null && listing == null && !inBazaar) mine = Verdict.Mine.OWNED;
				Verdict.Result r = resultFor(key, listing, config.valueUpgrades ? Valuation.parse(stack) : null, mine);
				if (r == null || r.lines().isEmpty()) return;
				if (mine != null && r.listPrice() != null) BidHelper.hoveredList(key, r.listPrice());
				if (!data.bazaarAll().containsKey(key)) {         // auction items: 7-day chart beside the menu
					Valuation.Attrs pa = config.valueUpgrades ? Valuation.parse(stack) : null;
					String lk = pa != null && pa.pet ? CoflClient.petLevelKey(key, pa.petLevel) : null;   // pets: same level range
					PriceChart.hovered(lk != null ? lk : key, stack.getHoverName().getString().replaceAll("§.", ""),
							listing != null ? listing.price() : null);
				}
				if (config.showFlipBanner && r.banner() != null && !lines.isEmpty()) lines.add(1, r.banner());   // under the item name
				if (r.nextBid() != null && r.bidCap() != null && r.maxBid() != null)
					BidHelper.hovered(key, r.suggestedBid(), r.nextBid(), r.bidCap(), r.maxBid(), r.lowestBin());
				lines.add(Component.empty());
				lines.addAll(r.lines());
			} catch (RuntimeException e) {          // a Not Enough Flips bug must never crash the game
				System.err.println("[NEF] tooltip: " + e);
			}
		});

		Highlight.init(config, data);
		OutbidHud.init(config);
		Alerts.init(config);
		ProfitLog.init(config);
		BzOrders.init(config, data);
		FlipFeed.init(config);
		Sniper.init(config);
		Bidders.init(config);
		MyWallet.init(config);
		DealsPanel.init(config);
		AhMarket.init();
		FlipPanel.init(config, data);
		SignFill.init(config, data);
		PriceChart.init(config);
		FcfPanel.init(config, data);
		Desk.init(config, data);
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("notenoughflips")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		System.out.println("[NEF] " + version + " loaded");
		boolean[] greeted = {false};
		int[] ticks = {0};
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Accounts.tick();                                               // another account → its own files
			Sniper.clientTick();                                           // auto-open that waited for chat to close
			if (client.player != null && ticks[0] % 200 == 0) {          // every 10 s
				try {
					MyWallet.tick();                                   // your bank (with your API key), every 5 min
					AhMarket.tick();                                   // AH coins traded per day (Bazaar → Market size)
					AhScanner.tick(config);                                // scans every scanMinutes
					BzOrders.check();                                      // bazaar orders outbid / undercut (every 30 s)
					if (config.setupDone) FlipFeed.tick(FlipPanel.budget());   // announce the best new AH flips
					Bazaar.ranking(config, data, FlipPanel.budget());          // keeps the 5-minute bazaar flip snapshot fresh
				} catch (RuntimeException e) {
					System.err.println("[NEF] tick: " + e);
				}
			}
			if (client.player != null && ++ticks[0] % 6000 == 1200) {     // every 5 min (1 min after joining)
				try {
					Double purse = PurseReporter.readPurse();
					if (purse != null) PurseLog.record(purse);
				} catch (RuntimeException ignored) {
					// not in SkyBlock
				}
			}
			if (greeted[0] || client.player == null) return;
			greeted[0] = true;                         // once per game start: proves the mod is loaded
			Chat.say(HudFormatter.line("[Not Enough Flips " + version + "] ", HudFormatter.GOLD,
					"loaded. Press E: FLIP (right of your inventory) shows flips that fit your purse; FCF is above it.", HudFormatter.GRAY));
			ProfitLog.dailySummary();
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			OutbidHud.onChat(message);
			Alerts.onChat(message);
		});
		BidHelper.register(config);
		Watch.start(config, data);
		Deals.start(config, data);
		PurseReporter.start(config);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				// Brigadier builders directly: Fabric renamed ClientCommandManager -> ClientCommands in 26.x
				LiteralArgumentBuilder.<FabricClientCommandSource>literal("nef")
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("watching").executes(ctx -> {
							var list = Watch.list();
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[NEF] Not watching any auctions."));
							for (Watch.Entry e : list) {
								ctx.getSource().sendFeedback(HudFormatter.line("[NEF] ", HudFormatter.GOLD, e.key, HudFormatter.WHITE,
										"  your bid " + HudFormatter.fmt(e.myBid) + "  lowest BIN " + HudFormatter.fmt(e.lastLowest)
												+ "  max profitable " + HudFormatter.fmt(e.maxProfitable), HudFormatter.GRAY));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwatch")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.greedyString())
										.executes(ctx -> {
											String k = StringArgumentType.getString(ctx, "item").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
											ctx.getSource().sendFeedback(Component.literal(Watch.remove(k)
													? "[NEF] Stopped watching " + k : "[NEF] " + k + " wasn't on the watch list"));
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("deals").executes(ctx -> {
							var list = Deals.list();
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal(
									"[NEF] No underpriced BINs right now. The FLIP panel's AH tab and the snipe alerts find them in game."));
							for (int i = 0; i < Math.min(10, list.size()); i++) {
								Deals.Deal d = list.get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA,
										(d.count() > 1 ? d.count() + "x " : "") + d.key(), HudFormatter.WHITE,
										String.format(Locale.ROOT, "  BIN %s, %.0f%% under fair, ", HudFormatter.fmt(d.price()), d.discount() * 100),
										HudFormatter.GRAY, "+" + HudFormatter.fmt(d.profit()), 0x55FF55));
							}
							if (!list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[NEF] /nef open <number> opens the auction."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("open")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("n", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											int n = IntegerArgumentType.getInteger(ctx, "n");
											var list = Deals.list();
											if (n > list.size()) {
												ctx.getSource().sendError(Component.literal("No deal #" + n + " (see /nef deals)"));
												return 0;
											}
											Cmd.viewAuction(list.get(n - 1).uuid());
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fcf").executes(ctx -> {
							var src = ctx.getSource();
							data.walletReport().whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
								for (Component c : FcfPanel.chat(d == null ? null : d.json(), d == null ? "no data" : d.source())) src.sendFeedback(c);
							}));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bz")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(HudFormatter.line("[NEF] Bazaar list: ", HudFormatter.GOLD,
											String.join(", ", Desk.bazaarList()), HudFormatter.WHITE));
									ctx.getSource().sendFeedback(Component.literal("[NEF] /nef bz add <id>  ·  /nef bz remove <id>  ·  /nef bz reset"));
									return 1;
								})
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset").executes(ctx -> {
									config.bazaarWatch = new java.util.ArrayList<>(java.util.Arrays.asList(Desk.DEFAULT_BAZAAR));
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Bazaar list reset to stocks + candy."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.greedyString())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
													java.util.List<String> l = Desk.bazaarList();
													if (!l.contains(id)) l.add(id);
													config.bazaarWatch = l;
													config.save();
													data.live(id);
													ctx.getSource().sendFeedback(Component.literal("[NEF] Added " + id + " to the Bazaar list."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.greedyString())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
													java.util.List<String> l = Desk.bazaarList();
													boolean removed = l.remove(id);
													config.bazaarWatch = l;
													config.save();
													ctx.getSource().sendFeedback(Component.literal(removed ? "[NEF] Removed " + id : "[NEF] " + id + " wasn't on the list"));
													return 1;
												}))))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flips").executes(ctx -> {
							FlipFeed.View v = FlipFeed.view(FlipPanel.budget());
							if (v.flips().isEmpty()) ctx.getSource().sendFeedback(Component.literal("[NEF] No AH flips fit your budget + risk right now"
									+ (v.checking() > 0 ? " (checking " + v.checking() + " more)." : ".")));
							for (int i = 0; i < Math.min(10, v.flips().size()); i++) {
								FlipFeed.Flip f = v.flips().get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA, f.c().name(), HudFormatter.WHITE,
										"  buy " + HudFormatter.fmt(f.c().price()) + " → " + HudFormatter.fmt(f.resale()), HudFormatter.GRAY,
										String.format(Locale.ROOT, "  +%s (%.0f%%)", HudFormatter.fmt(f.profit()), f.pct() * 100), 0x55FF55,
										"  " + Market.eta(f.liq().etaHours()), HudFormatter.DARK));
							}
							if (!v.flips().isEmpty()) ctx.getSource().sendFeedback(Component.literal("[NEF] /nef flip <number> opens the auction."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flip")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("n", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											int n = IntegerArgumentType.getInteger(ctx, "n");
											var list = FlipFeed.last();
											if (n > list.size()) {
												ctx.getSource().sendError(Component.literal("No flip #" + n + " (see /nef flips)"));
												return 0;
											}
											Cmd.viewAuction(list.get(n - 1).c().uuid());
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("bzflips").executes(ctx -> {
							var list = Bazaar.ranking(config, data, FlipPanel.budget());
							if (list.isEmpty()) ctx.getSource().sendFeedback(Component.literal("[NEF] No bazaar flips fit your budget (or the bazaar is still loading)."));
							for (int i = 0; i < Math.min(10, list.size()); i++) {
								Market.BzFlip f = list.get(i);
								ctx.getSource().sendFeedback(HudFormatter.line((i + 1) + ". ", HudFormatter.AQUA, Bazaar.name(f.id(), data), HudFormatter.WHITE,
										String.format(Locale.ROOT, "  order %,d × %s → offer %s", f.qty(), HudFormatter.fmt(f.order()), HudFormatter.fmt(f.offer())),
										HudFormatter.GRAY,
										String.format(Locale.ROOT, "  +%s total (%.0f%%) in ~%s ≈ +%s/h", HudFormatter.fmt(f.totalProfit()), f.pct() * 100,
												Market.dur(f.roundHours()), HudFormatter.fmt(f.profitPerHour())), 0x55FF55));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("profit").executes(ctx -> {
							for (int days : new int[]{0, 6, 29}) {
								ProfitLog.Summary sm = ProfitLog.summary(ProfitLog.startOfDay(days));
								ctx.getSource().sendFeedback(HudFormatter.line("[NEF] " + (days == 0 ? "Today" : (days + 1) + " days") + ": ", HudFormatter.GOLD,
										(sm.profit() >= 0 ? "+" : "") + HudFormatter.fmt(sm.profit()), sm.profit() >= 0 ? 0x55FF55 : 0xFF5555,
										" from " + sm.flips() + " flips" + (sm.best() != null ? ", best " + sm.best().item() + " +"
												+ HudFormatter.fmt(sm.best().profit()) : ""), HudFormatter.GRAY));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode")
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("simple").executes(ctx -> {
									config.simpleMode = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Simple mode: tooltips show only what to do."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("pro").executes(ctx -> {
									config.simpleMode = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Pro mode: tooltips show every number."));
									return 1;
								})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("setup").executes(ctx -> {
							config.setupDone = false;
							config.save();
							ctx.getSource().sendFeedback(Component.literal("[NEF] Press E and click FLIP (right of your inventory) for the 3-tap setup."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("feed")
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> {
									config.flipFeed = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] AH flip feed on (found during the AH scan)."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> {
									config.flipFeed = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] AH flip feed off."));
									return 1;
								})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("snipe").executes(ctx -> {
									if (Sniper.latest() == null) {
										ctx.getSource().sendFeedback(Component.literal("[NEF] No undervalued BIN alert yet."
												+ (config.sniper ? " Watching the AH." : " Alerts are off: /nef snipe on")));
									} else Sniper.openLatest();
									return 1;
								})
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> {
									config.sniper = true;
									config.flipFeed = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Undervalued-BIN alerts on."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> {
									config.sniper = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Undervalued-BIN alerts off."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("min")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("percent", IntegerArgumentType.integer(5, 95))
												.executes(ctx -> {
													config.snipeMinPercent = IntegerArgumentType.getInteger(ctx, "percent");
													config.save();
													ctx.getSource().sendFeedback(Component.literal("[NEF] Snipe alerts at " + (int) config.snipeMinPercent
															+ "% or more under the lowest BIN."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("max")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("price", StringArgumentType.word())
												.executes(ctx -> {
													Double v = parseAmount(StringArgumentType.getString(ctx, "price"));
													if (v == null) {
														ctx.getSource().sendFeedback(Component.literal("[NEF] Use a price like 20m, 500k or 0 (= your purse)."));
														return 0;
													}
													config.snipeMaxPrice = v;
													config.save();
													ctx.getSource().sendFeedback(Component.literal("[NEF] Snipes up to " + (v <= 0 ? "your purse" : HudFormatter.fmt(v)) + "."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("block")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.word())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").toUpperCase(Locale.ROOT);
													if (!config.snipeBlock.contains(id)) config.snipeBlock.add(id);
													config.snipeAllow.remove(id);
													config.save();
													ctx.getSource().sendFeedback(Component.literal("[NEF] No snipe alerts for " + id + "."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unblock")
										.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.word())
												.executes(ctx -> {
													String id = StringArgumentType.getString(ctx, "id").toUpperCase(Locale.ROOT);
													config.snipeBlock.remove(id);
													if (!config.snipeAllow.contains(id)) config.snipeAllow.add(id);
													config.save();
													ctx.getSource().sendFeedback(Component.literal("[NEF] " + id + " can be sniped again."));
													return 1;
												})))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("auto")
										.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> {
											config.snipeAutoOpen = true;
											config.save();
											ctx.getSource().sendFeedback(Component.literal("[NEF] Alerts open the auction by themselves (you still press Buy)."));
											return 1;
										}))
										.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> {
											config.snipeAutoOpen = false;
											config.save();
											ctx.getSource().sendFeedback(Component.literal("[NEF] Alerts only show the banner; /nef snipe opens it."));
											return 1;
										}))))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("apikey")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("key", StringArgumentType.word())
										.executes(ctx -> {
											String k = StringArgumentType.getString(ctx, "key").trim();
											if (!k.matches("[0-9a-fA-F-]{36}") && !k.equalsIgnoreCase("clear")) {
												ctx.getSource().sendFeedback(Component.literal("[NEF] That doesn't look like a Hypixel API key (xxxxxxxx-xxxx-…)."));
												return 0;
											}
											config.hypixelApiKey = k.equalsIgnoreCase("clear") ? "" : k;
											config.save();
											ctx.getSource().sendFeedback(Component.literal(config.hypixelApiKey.isEmpty() ? "[NEF] API key removed."
													: "[NEF] API key saved on this PC (config/notenoughflips.json). It's only sent to api.hypixel.net, "
															+ "for the bidders' coins panel. Never share that file."));
											return 1;
										})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("signals").executes(ctx -> {
							JsonObject sj = data.bzSignals();
							if (sj == null || !sj.has("signals")) {
								ctx.getSource().sendFeedback(Component.literal("[NEF] Bazaar signals are loading, try again in a few seconds."));
								return 1;
							}
							ctx.getSource().sendFeedback(Component.literal("[NEF] Bazaar signals (" + sj.getAsJsonArray("signals").size()
									+ "), best first. Full list + charts: Trade Desk → Market → Tips."));
							int n = 0;
							for (var e : sj.getAsJsonArray("signals")) {
								if (n++ >= 6) break;
								JsonObject o = e.getAsJsonObject();
								ctx.getSource().sendFeedback(HudFormatter.line(" " + n + ". ", HudFormatter.GRAY, HudFormatter.str(o, "title"), HudFormatter.WHITE,
										String.format(java.util.Locale.ROOT, " %+.1f%% · %s", o.get("edge").getAsDouble() * 100, HudFormatter.str(o, "conf")),
										"high".equals(HudFormatter.str(o, "conf")) ? HudFormatter.GREEN : HudFormatter.YELLOW));
								ctx.getSource().sendFeedback(HudFormatter.line("    " + HudFormatter.str(o, "text"), HudFormatter.GRAY));
							}
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("forecast").executes(ctx -> {
									int n = data.forecasts().size();
									long gen = data.forecastsGenerated();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Forecasts in flips: " + (config.forecastFlips ? "ON" : "OFF")
											+ " · " + n + " items forecast" + (gen > 0 ? " (made " + Math.max(0, (System.currentTimeMillis() - gen) / 60000) + "m ago)" : "")
											+ (n == 0 ? " · none yet: the public data site needs 12+ hours of history for a product" : "")
											+ " · /nef forecast on|off"));
									return 1;
								})
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> {
									config.forecastFlips = true;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Forecasts ON: AH and Bazaar flips count the expected price when you sell."));
									return 1;
								}))
								.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> {
									config.forecastFlips = false;
									config.save();
									ctx.getSource().sendFeedback(Component.literal("[NEF] Forecasts OFF: flips use live prices only."));
									return 1;
								})))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("desk").executes(ctx -> {
							Desk.toggle();
							ctx.getSource().sendFeedback(Component.literal(Desk.isOn() ? "[NEF] Trade Desk open: press E." : "[NEF] Trade Desk closed."));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("unwatchall").executes(ctx -> {
							Watch.clear();
							ctx.getSource().sendFeedback(Component.literal("[NEF] Watch list cleared."));
							return 1;
						}))
						.executes(ctx -> {
							var player = Minecraft.getInstance().player;
							String key = player == null ? null : ItemKeys.keyFor(player.getMainHandItem());
							if (key == null) {
								ctx.getSource().sendError(Component.literal("Hold a SkyBlock item, or use /nef <item name or id>"));
								return 0;
							}
							lookup(ctx.getSource(), key);
							return 1;
						})
						.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.greedyString())
								.executes(ctx -> {
									String q = StringArgumentType.getString(ctx, "item").trim();
									lookup(ctx.getSource(), q.toUpperCase(Locale.ROOT).replace(' ', '_'));
									return 1;
								}))));
	}

	/**
	 * Our doc, or a SkyCofl doc when ours is missing / has too little auction history.
	 * @param fetch false = never start a SkyCofl request (used for the many items the border check scans)
	 */
	static JsonObject docFor(String key, boolean fetch) {
		DataClient.Doc d = data.doc(key);
		JsonObject own = d == null ? null : d.json();
		if (own != null && !CoflClient.thin(own)) return own;
		if (d == null && !fetch) return null;
		if (own == null && d != null && data.live(key) != null) return null;   // a bazaar product we don't have
		JsonObject c = cofl.doc(key, fetch);
		if (c == null) return own;
		JsonObject model = HudFormatter.obj(own, "market", "model");
		if (model != null) HudFormatter.obj(c, "market").add("model", model);   // keep our per-copy valuation model
		return c;
	}

	/**
	 * A pet priced from sales of the same rarity and level range (SkyCofl, last 7 days), or null when that isn't loaded
	 * yet or fewer than 3 sold (then the level interpolation of Valuation is used).
	 */
	static JsonObject petDoc(String key, int level, boolean fetch) {
		String lk = CoflClient.petLevelKey(key, level);
		if (lk == null || cofl == null) return null;
		JsonObject d = cofl.doc(lk, fetch);
		if (d == null) return null;
		Double n7 = HudFormatter.num(HudFormatter.obj(d, "stats", "7d", "sold"), "n");
		if (n7 == null || n7 < 3) return null;
		d.addProperty("petLevels", lk.substring(lk.indexOf('@') + 1));
		d.addProperty("petSold7d", n7);
		return d;
	}

	/**
	 * Flip profit (after fees) of the BIN shown in an auction view, using the same verdict as the tooltip — only when it
	 * is a real PROFIT FLIP (sells often enough, reliable price, not manipulated). Null otherwise or while loading.
	 */
	static Double binProfit(net.minecraft.world.item.ItemStack stack) {
		String key = ItemKeys.keyFor(stack);
		if (key == null) return null;
		net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		List<Component> lines = new java.util.ArrayList<>();
		lines.add(stack.getHoverName());
		if (lore != null) lines.addAll(lore.lines());
		Verdict.Listing listing = Verdict.listing(lines, stack.getCount());
		if (listing == null || !listing.bin()) return null;
		Verdict.Result r = resultFor(key, listing, config.valueUpgrades ? Valuation.parse(stack) : null, null);
		if (r == null || r.banner() == null || r.profit() == null || r.profit() <= 0) return null;
		return r.profit();
	}

	private static Verdict.Result resultFor(String key, Verdict.Listing listing, Valuation.Attrs attrs, Verdict.Mine mine) {
		if (attrs != null && attrs.pet && attrs.petLevel > 0) {
			JsonObject pd = petDoc(key, attrs.petLevel, true);
			if (pd != null) {
				try {
					return HudFormatter.render(pd, null, config, listing, null, mine);   // priced from same-level sales
				} catch (RuntimeException e) {
					// fall through to the normal path
				}
			}
		}
		DataClient.Doc dd = data.doc(key);
		if (dd == null) return new Verdict.Result(LOADING, null);
		JsonObject j = docFor(key, true);
		if (j == null) return cofl.loading(key) ? new Verdict.Result(LOADING, null) : null;
		try {
			boolean bz = "bz".equals(j.get("kind").getAsString());
			DataClient.Live live = bz ? data.live(key) : null;
			Valuation.Estimate est = bz ? null : Valuation.estimate(j, key, attrs);
			return HudFormatter.render(j, live, config, listing, est, mine);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Your player name (for "Seller: you" on your own listings), or null. */
	/** "20m", "500k", "1.5b", "250000" → coins; null if unreadable. */
	static Double parseAmount(String t) {
		if (t == null) return null;
		String v = t.trim().toLowerCase(Locale.ROOT).replace(",", "");
		double mult = 1;
		if (v.endsWith("k")) mult = 1e3;
		else if (v.endsWith("m")) mult = 1e6;
		else if (v.endsWith("b")) mult = 1e9;
		if (mult > 1) v = v.substring(0, v.length() - 1);
		try {
			return Double.parseDouble(v) * mult;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static String playerName() {
		var p = Minecraft.getInstance().player;
		return p == null ? null : p.getName().getString();
	}

	private static boolean inBazaarMenu() {
		var s = BidHelper.currentScreen();          // tooltips only render while a screen is open
		if (s == null) return false;
		String t = s.getTitle().getString();
		return t.contains("Bazaar") || t.contains("➜");
	}

	private static void lookup(FabricClientCommandSource src, String key) {
		src.sendFeedback(HudFormatter.line("Looking up " + key + "…", HudFormatter.GRAY));
		data.docAsync(key).whenComplete((d, err) -> Minecraft.getInstance().execute(() -> {
			if (d == null || d.json() == null) {
				src.sendError(Component.literal("No market data for " + key
						+ ". Use the SkyBlock id (e.g. hyperion, enchanted diamond, booster cookie)."));
				return;
			}
			DataClient.Live live = "bz".equals(d.json().get("kind").getAsString()) ? data.live(key) : null;
			for (Component c : HudFormatter.chat(key, d.json(), live, config, d.source())) src.sendFeedback(c);
		}));
	}
}
