package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/** Live bazaar prices are the real top of the book; the spread ranking matches the public formula. */
class BazaarSpreadTest {

	@Test
	void pricesAreTheTopOfTheBookNotTheLaggingAverage() {
		String json = """
				{"success":true,"lastUpdated":1000,"products":{"ENCHANTED_DIAMOND":{
				 "sell_summary":[{"amount":64,"pricePerUnit":1500.1,"orders":2},{"amount":640,"pricePerUnit":1480,"orders":9}],
				 "buy_summary":[{"amount":10,"pricePerUnit":1590.9,"orders":1},{"amount":900,"pricePerUnit":1610,"orders":12}],
				 "quick_status":{"buyPrice":1620.5,"sellPrice":1470.2,"buyMovingWeek":700000,"sellMovingWeek":650000,"buyOrders":40,"sellOrders":55}}}}""";
		var m = DataClient.parseBazaar(JsonParser.parseString(json).getAsJsonObject());
		DataClient.Live l = m.get("ENCHANTED_DIAMOND");
		assertEquals(1590.9, l.buy(), 1e-9, "instant-buy price = cheapest sell offer");
		assertEquals(1500.1, l.sell(), 1e-9, "instant-sell price = best buy order");
	}

	@Test
	void fallsBackToQuickStatusWhenABookSideIsEmpty() {
		String json = """
				{"lastUpdated":1000,"products":{"X":{"sell_summary":[],"buy_summary":[],
				 "quick_status":{"buyPrice":10,"sellPrice":8,"buyMovingWeek":1,"sellMovingWeek":1,"buyOrders":1,"sellOrders":1}}}}""";
		DataClient.Live l = DataClient.parseBazaar(JsonParser.parseString(json).getAsJsonObject()).get("X");
		assertEquals(10, l.buy(), 1e-9);
		assertEquals(8, l.sell(), 1e-9);
	}

	@Test
	void spreadScoreIsSpreadMinusFeeTimesHourlySales() {
		// order 100, offer 110, 16,800 sold a week = 100/h: (110 − 100 − 1.375) × 100
		assertEquals(862.5, Bazaar.spreadScore(100, 110, 16_800), 1e-9);
		assertEquals(0, Bazaar.spreadScore(110, 100, 16_800), 1e-9, "no spread, no score");
		assertEquals(0, Bazaar.spreadScore(100, 110, 0), 1e-9);
	}

	@Test
	void manipulationFlag() {
		assertTrue(Bazaar.spreadManipulated(2_100, 1_000), "over 2× its usual price");
		assertFalse(Bazaar.spreadManipulated(1_600, 1_000), "1.6× is fine for a cheap item");
		assertTrue(Bazaar.spreadManipulated(16_000_000, 10_000_000), "1.6× on an item over 7.5M");
		assertFalse(Bazaar.spreadManipulated(2_100, 0), "no history: no flag");
	}

	@Test
	void streamingParseMatchesTheOrderBook() {
		// random books: the streamed numbers must equal what the old tree code computed with top() and near()
		java.util.Random rnd = new java.util.Random(3);
		StringBuilder sb = new StringBuilder("{\"lastUpdated\":5,\"products\":{");
		for (int i = 0; i < 40; i++) {
			if (i > 0) sb.append(',');
			sb.append("\"P").append(i).append("\":{\"product_id\":\"P").append(i).append("\",");
			double base = 1 + rnd.nextInt(100_000);
			for (String side : new String[]{"sell_summary", "buy_summary"}) {
				sb.append('"').append(side).append("\":[");
				int n = rnd.nextInt(6);
				for (int k = 0; k < n; k++) {
					if (k > 0) sb.append(',');
					double pr = side.equals("sell_summary") ? base * (1 - 0.003 * k) : base * (1.02 + 0.003 * k);
					sb.append("{\"orders\":").append(1 + rnd.nextInt(5)).append(",\"amount\":7,\"pricePerUnit\":").append(pr).append('}');
				}
				sb.append("],");
			}
			sb.append("\"quick_status\":{\"productId\":\"P").append(i).append("\",\"buyPrice\":").append(base * 1.05)
					.append(",\"sellPrice\":").append(base * 0.97).append(",\"buyMovingWeek\":").append(i * 10)
					.append(",\"sellMovingWeek\":").append(i * 7).append(",\"buyOrders\":").append(i).append(",\"sellOrders\":").append(i + 1).append("}}");
		}
		var root = JsonParser.parseString(sb.append("}}").toString()).getAsJsonObject();
		var m = DataClient.parseBazaar(root);
		assertEquals(40, m.size());
		for (int i = 0; i < 40; i++) {
			var pr = root.getAsJsonObject("products").getAsJsonObject("P" + i);
			var q = pr.getAsJsonObject("quick_status");
			DataClient.Live l = m.get("P" + i);
			double offer = DataClient.top(pr.get("buy_summary")), order = DataClient.top(pr.get("sell_summary"));
			assertEquals(offer > 0 ? offer : q.get("buyPrice").getAsDouble(), l.buy(), 1e-9);
			assertEquals(order > 0 ? order : q.get("sellPrice").getAsDouble(), l.sell(), 1e-9);
			assertEquals(DataClient.near(pr.get("buy_summary"), false), l.nearOffers());
			assertEquals(DataClient.near(pr.get("sell_summary"), true), l.nearOrders());
			assertEquals(i * 10, l.buyWeek(), 1e-9);
			assertEquals(i + 1, l.orders());
			assertEquals(5, l.ts());
		}
	}
}
