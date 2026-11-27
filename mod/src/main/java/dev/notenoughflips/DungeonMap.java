package dev.notenoughflips;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Dungeon minimap: the "Magical Map" Hypixel puts in your last hotbar slot, drawn in the top-left corner so you can
 * see it without holding it. It shows exactly what that map shows (rooms you've explored, doors, player markers),
 * nothing it doesn't: no scanning for unexplored rooms, secrets or the Mimic.
 */
final class DungeonMap {
	private DungeonMap() {}

	private static NefConfig cfg;
	/** Map brightness by the low two bits of a packed colour: low, normal, high, lowest. */
	private static final int[] SHADE = {180, 220, 255, 135};

	static void init(NefConfig config) {
		cfg = config;
		HudHook.register("dungeon_map", (p, size) -> {
			try {
				paint(p);
			} catch (RuntimeException e) {
				System.err.println("[NEF] dungeon map: " + e);
			}
		});
	}

	/** ARGB for a packed map colour (base colour × 4 + shade), 0 = nothing drawn there. */
	static int argb(int packed, int baseRgb) {
		int base = (packed & 0xFF) >> 2;
		if (base == 0) return 0;
		int m = SHADE[packed & 3];
		int r = ((baseRgb >> 16) & 0xFF) * m / 255, g = ((baseRgb >> 8) & 0xFF) * m / 255, b = (baseRgb & 0xFF) * m / 255;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/**
	 * Pure: rows of same-coloured runs of a 128×128 map, as {x, y, length, argb}, skipping empty pixels.
	 * @param rgb base colour id → RGB
	 */
	static java.util.List<int[]> runs(byte[] colors, java.util.function.IntUnaryOperator rgb) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		for (int y = 0; y < 128; y++) {
			int x = 0;
			while (x < 128) {
				int packed = colors[x + y * 128] & 0xFF;
				int col = argb(packed, rgb.applyAsInt(packed >> 2));
				int end = x + 1;
				while (end < 128 && (colors[end + y * 128] & 0xFF) == packed) end++;
				if (col != 0) out.add(new int[]{x, y, end - x, col});
				x = end;
			}
		}
		return out;
	}

	private static byte[] lastColors;
	private static int lastHash;
	private static java.util.List<int[]> cache = java.util.List.of();

	private static MapItemSavedData data() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		ItemStack st = mc.player.getInventory().getItem(8);
		if (st == null || st.isEmpty() || !(st.getItem() instanceof MapItem)) return null;
		if (!st.getHoverName().getString().contains("Magical Map")) return null;          // Hypixel's dungeon map
		return MapItem.getSavedData(st, mc.level);
	}

	private static void paint(Highlight.Painter p) {
		if (cfg == null || !cfg.dungeonMap) return;
		Minecraft mc = Minecraft.getInstance();
		if (BidHelper.currentScreen() != null || mc.options.hideGui) return;
		MapItemSavedData d = data();
		if (d == null) return;
		byte[] colors = d.colors;
		int h = java.util.Arrays.hashCode(colors);
		if (colors != lastColors || h != lastHash) {                       // the map changed: redo the runs
			lastColors = colors;
			lastHash = h;
			cache = runs(colors, id -> MapColor.byId(id).col);
		}
		int size = Math.max(64, Math.min(256, cfg.dungeonMapSize));
		int x0 = 4, y0 = 4;
		p.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, 0xB0000000);
		for (int[] r : cache) {
			int x1 = x0 + r[0] * size / 128, x2 = x0 + (r[0] + r[2]) * size / 128;
			int y1 = y0 + r[1] * size / 128, y2 = y0 + (r[1] + 1) * size / 128;
			if (x2 > x1 && y2 > y1) p.fill(x1, y1, x2, y2, r[3]);
		}
		for (MapDecoration dec : d.getDecorations()) {                     // player markers
			int mx = x0 + (dec.x() + 128) * size / 256, my = y0 + (dec.y() + 128) * size / 256;
			p.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFF000000);
			p.fill(mx - 1, my - 1, mx + 1, my + 1, 0xFF55FF55);
		}
	}
}
