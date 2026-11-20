package dev.notenoughflips;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

/**
 * Pre-fills Hypixel's sign inputs for the bazaar flip you picked in the FLIP panel (the same idea as the bid preset):
 *
 *   Search sign            → the item's name        (you clicked Search in the Bazaar)
 *   Custom amount sign     → how many to order       (volume-sized, live)
 *   Custom price sign      → buy order price          (top order + 0.1, live)
 *
 * Only the text is filled in. You open each sign and press Done yourself; nothing is clicked or sent for you.
 * Which sign it is comes from Hypixel's own hint lines under the input (e.g. "Enter query", "Enter amount").
 */
final class SignFill {
	private static NefConfig cfg;
	private static DataClient data;
	private static volatile String lastContainerTitle = "";

	private SignFill() {}

	static void init(NefConfig config, DataClient client) {
		cfg = config;
		data = client;
	}

	/** Remember the menu you were in before the sign opened (Bazaar search vs product page). */
	static void containerOpened(Screen s) {
		lastContainerTitle = s.getTitle().getString();
	}

	/** The sign's text lines (the screen's own String[4]), found by type so it works with every mapping. */
	private static String[] lines(Screen s) {
		for (Class<?> c = s.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.getType() != String[].class || Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(s);
					if (v instanceof String[] a && a.length == 4) return a;
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// try the next field
				}
			}
		}
		return null;
	}

	/** Pure: what to type on line 1, given the sign's hint lines and the flip you picked. Null = leave it alone. */
	static String choose(String[] lines, String title, String name, long qty, double price) {
		StringBuilder hint = new StringBuilder();
		for (int i = 1; i < lines.length; i++) if (lines[i] != null) hint.append(' ').append(lines[i].toLowerCase(Locale.ROOT));
		String h = hint.toString();
		if (lines[0] != null && !lines[0].isBlank()) return null;                  // you already typed something
		if (h.contains("query") || h.contains("search")) return name;
		if (h.contains("amount") || h.contains("quantity")) return qty > 0 ? Long.toString(qty) : null;
		if (h.contains("price") || h.contains("coins")) return String.format(Locale.ROOT, "%.1f", price);
		// no hint: in the Bazaar but not on the product's page yet → it's the search sign
		String t = title.toLowerCase(Locale.ROOT);
		if ((t.contains("bazaar") || t.contains("➜")) && !t.contains(name.toLowerCase(Locale.ROOT))) return name;
		return null;
	}

	static void signOpened(Screen s) {
		try {
			if (cfg == null || !cfg.signFill || !(s instanceof AbstractSignEditScreen)) return;
			String t = lastContainerTitle;
			String lt = t.toLowerCase(Locale.ROOT);
			if (cfg.listPriceFill && lt.contains("create") && lt.contains("auction")) {      // the BIN price sign
				ListPrice.Suggestion g = ListPrice.forSign();
				String[] lines = g == null ? null : lines(s);
				if (lines == null || lines[0] != null && !lines[0].isBlank()) return;
				lines[0] = Long.toString(g.total());
				Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "filled in " + String.format(Locale.ROOT, "%,d", g.total())
						+ " (" + g.why() + "). ", HudFormatter.WHITE, "Change it if you like, then press Done.", HudFormatter.GRAY));
				return;
			}
			String id = FlipPanel.pendingId(), name = FlipPanel.pendingName();
			if (id == null || name == null) return;
			if (!t.contains("Bazaar") && !t.contains("➜")) return;               // only for bazaar signs
			String[] lines = lines(s);
			if (lines == null) return;
			DataClient.Live live = data.live(id);
			Market.BzFlip f = live == null ? null : Bazaar.flip(cfg, id, live, FlipPanel.budget());
			String text = choose(lines, t, name, f == null ? 0 : f.qty(), f == null ? 0 : f.order());
			if (text == null) return;
			lines[0] = text;
			Chat.say(HudFormatter.line("[NEF] ", HudFormatter.GOLD, "filled in \"" + text + "\" for " + name + ". ", HudFormatter.WHITE,
					"Check it and press Done.", HudFormatter.GRAY));
		} catch (RuntimeException e) {
			System.err.println("[NEF] sign fill: " + e);
		}
	}
}
