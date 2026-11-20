package dev.notenoughflips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

/**
 * Small "Discord" button at the bottom right of your inventory: opens the Not Enough Flips Discord (the game asks
 * you to confirm before opening the link in your browser). Turn it off with discordButton=false in the config.
 */
final class DiscordButton {
	static final String URL = "https://discord.gg/RMK8cHmWUD";
	private static final int BLURPLE = 0xFF5865F2, H = 12;
	private static NefConfig cfg;

	private DiscordButton() {}

	static void init(NefConfig config) {
		cfg = config;
	}

	/** {x1, y1, x2, y2} under the inventory's bottom-right corner (beside it when there's no room below), or null. */
	private static int[] rect(Screen s) {
		if (cfg == null || !cfg.discordButton || !(s instanceof InventoryScreen)) return null;
		int[] box = GuiPos.box(s);
		int w = Minecraft.getInstance().font.width("Discord") + 10;
		int x2 = box[0] + box[2], y2 = box[1] + box[3];
		if (y2 + 2 + H <= s.height) return new int[]{x2 - w, y2 + 2, x2, y2 + 2 + H};
		return new int[]{x2 + 2, y2 - H, x2 + 2 + w, y2};
	}

	static void paint(Screen s, Highlight.Painter p) {
		int[] r = rect(s);
		if (r == null) return;
		p.fill(r[0], r[1], r[2], r[3], BLURPLE);
		p.fill(r[0], r[3] - 1, r[2], r[3], 0xFF3C45A5);
		p.text("Discord", r[0] + 5, r[1] + 2, 0xFFFFFFFF);
	}

	static boolean click(Screen s, double mx, double my) {
		int[] r = rect(s);
		if (r == null || mx < r[0] || mx >= r[2] || my < r[1] || my >= r[3]) return false;
		ConfirmLinkScreen.confirmLinkNow(s, URL);
		return true;
	}
}
