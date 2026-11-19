package dev.notenoughflips;

/**
 * The look of every panel the mod adds around the inventory: the same light grey container, bevelled edges and
 * stone buttons as Minecraft's own menus, with dark text. Colours that sit on the grey are darkened versions of the
 * chat colours so they stay readable.
 */
final class McStyle {
	static final int PANEL = 0xFFC6C6C6, TEXT = 0xFF404040, MUTED = 0xFF6E6E6E, DIM = 0xFF8A8A8A, LINE = 0xFF9E9E9E,
			GOOD = 0xFF1F7F1F, WARN = 0xFF8A7400, BAD = 0xFFAA2020, GOLD = 0xFF9C6500, BLUE = 0xFF2A55A8,
			BTN_TEXT = 0xFFE0E0E0, BTN_SEL = 0xFFFFFF55;

	private McStyle() {}

	/** A menu container: black edge, white top-left highlight, grey bottom-right shadow (like the inventory). */
	static void panel(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, 0xFF000000);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF555555);
		p.fill(x1 + 1, y1 + 1, x2 - 2, y2 - 2, 0xFFFFFFFF);
		p.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, PANEL);
	}

	/** A sunken box like an item slot. */
	static void slot(Highlight.Painter p, int x1, int y1, int x2, int y2) {
		p.fill(x1, y1, x2, y2, 0xFFFFFFFF);
		p.fill(x1, y1, x2 - 1, y2 - 1, 0xFF373737);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF8B8B8B);
	}

	/** A stone button; selected = white frame and yellow text, like a hovered button in the game's menus. */
	static void button(Highlight.Painter p, int x1, int y1, int x2, int y2, String label, boolean sel) {
		p.fill(x1, y1, x2, y2, sel ? 0xFFFFFFFF : 0xFF000000);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF6F6F6F);
		p.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, 0xFFA8A8A8);
		p.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, 0xFF4A4A4A);
		var font = net.minecraft.client.Minecraft.getInstance().font;
		p.text(label, x1 + (x2 - x1 - font.width(label)) / 2, y1 + (y2 - y1 - 8) / 2 + 1, sel ? BTN_SEL : BTN_TEXT);
	}
}
