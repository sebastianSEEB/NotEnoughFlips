package dev.notenoughflips;

/**
 * The look of every panel the mod adds around the inventory (the original FCF look): a dark see-through panel with a
 * blue edge line top and bottom, white text, chat colours for numbers, dark chips with a blue underline for tabs and
 * small buttons, and gold-framed buttons for the main toggles (FLIP, FCF, DEALS, DESK).
 */
final class McStyle {
	static final int PANEL = 0xE0101418, EDGE = 0xFF3987E5, TEXT = 0xFFFFFFFF, MUTED = 0xFFAAAAAA, DIM = 0xFF6E6E6E,
			LINE = 0xFF333333, GOOD = 0xFF55FF55, WARN = 0xFFFFFF55, BAD = 0xFFFF5555, GOLD = 0xFFFFAA00, BLUE = 0xFF7FB4F0,
			CHIP = 0xE0202830, BTN_TEXT = 0xFFCCCCCC, BTN_SEL = 0xFFFFFFFF;

	private McStyle() {}

	/** A panel: dark see-through fill, blue line top and bottom. */
	static void panel(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, PANEL);
		p.fill(x1, y1, x2, y1 + 1, EDGE);
		p.fill(x1, y2 - 1, x2, y2, EDGE);
	}

	/** An inset box (stat tiles, plot areas): a bit darker, thin grey frame. */
	static void slot(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, 0xFF2A2F36);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xF0181C22);
	}

	/** A tab / small button: dark chip with a blue underline; selected = blue with a white underline. */
	static void button(Highlight.Painter p, int x1, int y1, int x2, int y2, String label, boolean sel) {
		p.fill(x1, y1, x2, y2, sel ? EDGE : CHIP);
		p.fill(x1, y2 - 1, x2, y2, sel ? 0xFFFFFFFF : EDGE);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		p.text(label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2 + 1, sel ? BTN_SEL : BTN_TEXT);
	}

	/** One of the main toggle buttons around the inventory (FLIP, FCF, DEALS, DESK): gold frame, blue when open. */
	static void toggle(Highlight.Painter p, int x1, int y1, int x2, int y2, String label, boolean open) {
		p.fill(x1, y1, x2, y2, open ? EDGE : 0xF0202830);
		p.fill(x1, y1, x2, y1 + 1, GOLD);
		p.fill(x1, y2 - 1, x2, y2, GOLD);
		p.fill(x1, y1, x1 + 1, y2, GOLD);
		p.fill(x2 - 1, y1, x2, y2, GOLD);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		p.text(label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2 + 1, open ? TEXT : GOLD);
	}
}
