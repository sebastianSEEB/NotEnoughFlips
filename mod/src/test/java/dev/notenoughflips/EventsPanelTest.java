package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

class EventsPanelTest {
	private static JsonObject product(String id, boolean ok, int buy, int sell) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("ok", ok);
		o.addProperty("buy_bin", buy);
		o.addProperty("sell_bin", sell);
		o.addProperty("gain", 0.12);
		return o;
	}

	/** Real time at the start of slot b in some year. */
	private static long at(int b) {
		return (Season.EPOCH_S + 400 * Season.YEAR_S + b * Season.SLOT_S + 60) * 1000L;
	}

	@Test
	void buyNowSellNowAndUpcoming() {
		JsonArray a = new JsonArray();
		a.add(product("CANDY", true, 20, 40));          // cheap now
		a.add(product("PUMPKIN", true, 5, 20));         // dear now
		a.add(product("SOON", true, 22, 30));           // buy window in ~4h
		a.add(product("LATER", true, 50, 55));          // far away
		a.add(product("NOISE", false, 20, 40));         // not tradeable: never prompted
		List<EventsPanel.Prompt> p = EventsPanel.prompts(a, at(20), 12 * 3_600_000L);
		assertEquals(3, p.size());
		assertEquals("BUY", p.get(0).action());
		assertEquals("CANDY", p.get(0).id());
		assertEquals("SELL", p.get(1).action());
		assertEquals("PUMPKIN", p.get(1).id());
		assertEquals("SOON", p.get(2).id());
		assertEquals(2 * Season.SLOT_S * 1000L - 60_000L, p.get(2).inMs());
		assertTrue(p.get(0).text().contains("+12%"));
	}

	@Test
	void windowStaysOpenForTwoSlots() {
		JsonArray a = new JsonArray();
		a.add(product("CANDY", true, 20, 40));
		assertEquals("BUY", EventsPanel.prompts(a, at(21), 0).get(0).action());
		assertEquals(0, EventsPanel.prompts(a, at(23), 0).size());
	}
}
