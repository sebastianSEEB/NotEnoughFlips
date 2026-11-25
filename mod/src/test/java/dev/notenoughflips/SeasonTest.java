package dev.notenoughflips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The SkyBlock calendar in the mod must match the data job's (tools/seasonality.py). */
class SeasonTest {
	private static final long E = Season.EPOCH_S * 1000;

	@Test
	void calendar() {
		assertEquals("Early Spring 1, year 1", Season.date(E));
		assertEquals("Early Spring 2, year 1", Season.date(E + 1_200_000));          // a day is 20 minutes
		assertEquals("Spring 1, year 1", Season.date(E + 31 * 1_200_000L));
		assertEquals("Early Spring 1, year 2", Season.date(E + Season.YEAR_S * 1000));
		assertEquals(0, Season.bin(E));
		assertEquals(1, Season.bin(E + Season.YEAR_S * 1000 + 3 * 3_600_000L));
		assertEquals(61, Season.bin(E - 1000));
	}

	@Test
	void untilASlot() {
		long t = E + 10 * Season.SLOT_S * 1000 + 30 * 60_000;                         // 30 min into slot 10
		assertEquals(0, Season.until(t, 10));
		assertEquals(90 * 60_000, Season.until(t, 11));
		assertEquals((Season.YEAR_S - Season.SLOT_S) * 1000 - 30 * 60_000, Season.until(t, 9), "next year");
		assertTrue(Season.dur(90 * 60_000).equals("1h 30m"));
		assertTrue(Season.dur(50L * 3_600_000).equals("2d 2h"));
	}

	@Test
	void slotLabelsMatchTheDataJob() {
		// seasonality.py: bin_label(b) is the date at the start of slot b (6 SkyBlock days each); Spooky = slots 40-41
		assertEquals("Early Spring 1", Season.label(0));
		assertEquals("Autumn 24", Season.label(40));
		assertEquals("Autumn 30", Season.label(41));
	}
}
